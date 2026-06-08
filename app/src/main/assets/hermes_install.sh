#!/bin/sh
# Hermes Agent Bootstrap Install Script v2
# Target: Termux bootstrap (dash/sh compatible)
# No bash-specific syntax — tested against dash
set -e

HERMES_HOME="${HERMES_HOME:-/data/user/0/com.opensiri.agent.bootstrap/files/home}"
USR_DIR="${USR_DIR:-/data/user/0/com.opensiri.agent.bootstrap/files/usr}"
TMPDIR="$USR_DIR/tmp"
LOG="$TMPDIR/hermes_install.log"
PHASE_FILE="$TMPDIR/install_phase"

mkdir -p "$TMPDIR" "$USR_DIR"

# ── Phase reporting (read by UI) ──
phase() {
    echo "[phase:$1] $2" | tee -a "$LOG"
    echo "$1" > "$PHASE_FILE"
}

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
phase "0" "Checking environment"

log "HOME=$HOME"
log "PREFIX=$PREFIX"
log "SHELL=$SHELL"

# Ensure basic tools
for tool in curl tar; do
    ensure_cmd "$tool" || { log "FATAL: cannot install $tool"; exit 1; }
done

# ═══════════════════════════════════════════
# PHASE 1: Install system dependencies
# ═══════════════════════════════════════════
phase "1" "Installing system dependencies"

ensure_cmd "git" "git" || { log "FATAL: git install failed"; exit 1; }
ensure_cmd "node" "nodejs-lts" || { log "FATAL: node install failed"; exit 1; }

log "Node.js: $(node --version 2>/dev/null || echo 'unknown')"
log "npm: $(npm --version 2>/dev/null || echo 'unknown')"
log "git: $(git --version 2>/dev/null || echo 'unknown')"

# ═══════════════════════════════════════════
# PHASE 2: Clone Hermes Agent
# ═══════════════════════════════════════════
phase "2" "Downloading Hermes Agent source"

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
    log "Cloning $HERMES_REPO..."
    clone_ok=0
    for attempt in 1 2 3; do
        log "Attempt $attempt/3..."
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
log "✓ Repository ready"

# ═══════════════════════════════════════════
# PHASE 3: Install npm dependencies
# ═══════════════════════════════════════════
phase "3" "Installing Node.js dependencies"

cd "$HERMES_DIR" || { log "FATAL: cannot enter $HERMES_DIR"; exit 1; }

log "Running npm install..."
if npm install --production >> "$LOG" 2>&1; then
    log "✓ npm install succeeded"
else
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
phase "4" "Building Hermes Agent"

log "Running npm run build..."
npm run build >> "$LOG" 2>&1 || log "⚠ Build step had issues (may be optional)"

log "Linking hermes CLI..."
npm link >> "$LOG" 2>&1 || {
    log "npm link failed, trying manual symlink..."
    ln -sf "$HERMES_DIR/bin/hermes.js" "$PREFIX/bin/hermes" 2>/dev/null || true
}

# ═══════════════════════════════════════════
# PHASE 5: Verify
# ═══════════════════════════════════════════
phase "5" "Verifying installation"

if command -v hermes >/dev/null 2>&1; then
    log "✓ hermes CLI found: $(command -v hermes)"
    hermes --version >> "$LOG" 2>&1 || log "⚠ version check failed (may be ok)"
else
    log "✗ hermes not in PATH"
    log "Try: export PATH=\$PATH:$PREFIX/bin"
    exit 1
fi

# ═══════════════════════════════════════════
# DONE
# ═══════════════════════════════════════════
phase "done" "Installation complete"
log "Hermes Agent installed successfully!"
log "Run: hermes --help"
