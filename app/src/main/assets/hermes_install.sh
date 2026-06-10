#!/bin/sh
# Hermes Agent Bootstrap Install Script v2
# Target: Termux bootstrap (dash/sh compatible)
# No bash-specific syntax — tested against dash
set -e

# PACKAGE is exported by InstallViewModel (from BuildConfig.APPLICATION_ID),
# which knows the correct applicationId including the ".bootstrap" / ".complete"
# flavor suffix. Fallback is the dev-debug applicationId.
PACKAGE="${PACKAGE:-com.opensiri.agent.bootstrap.bootstrap}"
HERMES_HOME="${HERMES_HOME:-/data/user/0/$PACKAGE/files/home}"
USR_DIR="${USR_DIR:-/data/user/0/$PACKAGE/files/usr}"
TMPDIR="$USR_DIR/tmp"
LOG="$TMPDIR/hermes_install.log"
PHASE_FILE="$TMPDIR/install_phase"

mkdir -p "$TMPDIR" "$USR_DIR"

# ── Phase reporting (read by UI) ──
phase() {
    echo "[phase:$1] $2" | tee -a "$LOG"
    echo "$1" > "$PHASE_FILE"
}

step() { echo "[step:$1] $2" | tee -a "$LOG"; }
progress() { echo "[progress:$1/$2] $3" | tee -a "$LOG"; }

log() { echo "  $*" | tee -a "$LOG"; }

# ── Check command exists, install if missing ──
ensure_cmd() {
    cmd="$1"
    pkg_name="${2:-$1}"
    if command -v "$cmd" >/dev/null 2>&1; then
        log "✓ $cmd: $(command -v "$cmd")"
        return 0
    fi
    log "Installing $pkg_name..."
    if ! pkg install -y "$pkg_name" >> "$LOG" 2>&1; then
        log "✗ Failed to install $pkg_name"
        return 1
    fi
    log "✓ $cmd installed"
}

# ── Download with retry, progress callback ──
download_with_retry() {
    url="$1"
    dest="$2"
    label="$3"
    max_attempts=3
    
    for attempt in $(seq 1 $max_attempts); do
        log "Downloading $label (attempt $attempt/$max_attempts)..."
        if curl -fSL --progress-bar -o "$dest" "$url" 2>> "$LOG"; then
            size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null || echo "?")
            log "✓ $label: ${size} bytes"
            return 0
        fi
        log "Attempt $attempt failed, retrying in 5s..."
        sleep 5
    done
    log "✗ Failed to download $label after $max_attempts attempts"
    return 1
}

# ═══════════════════════════════════════════
# PHASE 0: Sanity Check
# ═══════════════════════════════════════════
phase "0" "Checking environment (est. 0.5 MB)"

log "HOME=$HOME"
log "PREFIX=$PREFIX"
log "SHELL=$SHELL"

# Ensure basic tools
step "0.0" "Checking curl"
for tool in curl tar; do
    ensure_cmd "$tool" || { log "FATAL: cannot install $tool"; exit 1; }
done
step "0.1" "Environment ready"

# ═══════════════════════════════════════════
# PHASE 1: Install system dependencies
# ═══════════════════════════════════════════
phase "1" "Installing system dependencies (est. 61 MB)"

step "1.0" "Installing git (~15 MB)"
ensure_cmd "git" "git" || { log "FATAL: git install failed"; exit 1; }

step "1.1" "Installing Node.js + npm (~46 MB)"
ensure_cmd "node" "nodejs-lts" || { log "FATAL: node install failed"; exit 1; }

log "Node.js: $(node --version 2>/dev/null || echo 'unknown')"
log "npm: $(npm --version 2>/dev/null || echo 'unknown')"
log "git: $(git --version 2>/dev/null || echo 'unknown')"

# ═══════════════════════════════════════════
# PHASE 2: Clone Hermes Agent
# ═══════════════════════════════════════════
phase "2" "Downloading Hermes Agent source (est. 8 MB)"

HERMES_REPO="https://github.com/nousresearch/hermes-agent.git"
HERMES_DIR="$USR_DIR/hermes-agent"

if [ -d "$HERMES_DIR/.git" ]; then
    log "Existing repo found, updating..."
    (cd "$HERMES_DIR" && git pull --ff-only >> "$LOG" 2>&1) || {
        log "Pull failed, re-cloning..."
        rm -rf "$HERMES_DIR"
    }
fi

if [ ! -d "$HERMES_DIR" ]; then
    step "2.0" "Cloning repository ($HERMES_REPO)"
    log "Cloning $HERMES_REPO..."
    clone_ok=0
    for attempt in 1 2 3; do
        step "2.${attempt}" "Clone attempt $attempt/3"
        if timeout 300 git clone --depth 1 "$HERMES_REPO" "$HERMES_DIR" >> "$LOG" 2>&1; then
            log "✓ Clone succeeded"
            clone_ok=1
            break
        fi
        log "Clone attempt $attempt failed"
        [ "$attempt" != "3" ] && sleep 5
    done
    if [ "$clone_ok" = "0" ]; then
        log "✗ All clone attempts failed. Check network."
        exit 1
    fi
fi

# Verify clone succeeded
if [ ! -f "$HERMES_DIR/package.json" ]; then
    log "✗ Clone appears incomplete — package.json missing"
    exit 1
fi
step "2.4" "Repository ready"
log "✓ Repository ready"

# ═══════════════════════════════════════════
# PHASE 3: Install npm dependencies
# ═══════════════════════════════════════════
phase "3" "Installing Node.js dependencies (est. 85 MB)"

cd "$HERMES_DIR" || { log "FATAL: cannot enter $HERMES_DIR"; exit 1; }

step "3.0" "Running npm install (~85 MB download)"
log "Running npm install..."
if npm install --production >> "$LOG" 2>&1; then
    log "✓ npm install succeeded"
else
    step "3.1" "Retrying with --legacy-peer-deps"
    log "⚠ npm install had warnings — attempting with --legacy-peer-deps"
    if npm install --production --legacy-peer-deps >> "$LOG" 2>&1; then
        log "✓ npm install succeeded (with legacy peer deps)"
    else
        log "✗ npm install failed. See $LOG"
        exit 1
    fi
fi

# ═══════════════════════════════════════════
# PHASE 4: Build & Link
# ═══════════════════════════════════════════
phase "4" "Building Hermes Agent (est. 2 MB)"

step "4.0" "Compiling TypeScript"
log "Running npm run build..."
npm run build >> "$LOG" 2>&1 || log "⚠ Build step had issues (may be optional)"

step "4.1" "Linking hermes CLI"
log "Linking hermes CLI..."
npm link >> "$LOG" 2>&1 || {
    log "npm link failed, trying manual symlink..."
    ln -sf "$HERMES_DIR/bin/hermes.js" "$PREFIX/bin/hermes" 2>/dev/null || true
}

# ═══════════════════════════════════════════
# PHASE 5: Verify
# ═══════════════════════════════════════════
phase "5" "Verifying installation (est. 0.1 MB)"

step "5.0" "Checking hermes CLI"
if command -v hermes >/dev/null 2>&1; then
    log "✓ hermes CLI found: $(command -v hermes)"
    hermes --version >> "$LOG" 2>&1 || log "⚠ version check failed (may be ok)"
else
    log "✗ hermes not in PATH"
    log "Try: export PATH=\$PATH:${PREFIX}/bin"
    exit 1
fi
step "5.1" "Verification complete"

# ═══════════════════════════════════════════
# DONE
# ═══════════════════════════════════════════
phase "done" "Installation complete"
log "Hermes Agent installed successfully!"
log "Run: hermes --help"
