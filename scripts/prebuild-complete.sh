#!/usr/bin/env bash
#
# Pre-build script for the Complete (offline) flavor.
# Downloads all Termux .deb packages and Hermes Agent source code
# into app/src/complete/assets/ so they can be pre-bundled in the APK.
#
# The complete APK (~225 MB) requires zero internet on first launch.
#
# Usage:
#   ./scripts/prebuild-complete.sh
#
# After running this, build with:
#   ./gradlew assembleCompleteDebug

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/complete/assets/complete"
DEBS_DIR="$ASSETS_DIR/debs"
HERMES_DIR="$ASSETS_DIR/hermes-agent"

echo "=== Open Siri — Complete Flavor Pre-Build ==="
echo "Assets dir: $ASSETS_DIR"

mkdir -p "$DEBS_DIR"

# ── Download Termux .deb packages ─────────────────────────────────────────
download_deb() {
    local pkg="$1"
    local dest="$DEBS_DIR/${pkg}*.deb"

    # Skip if already downloaded
    if ls "$DEBS_DIR"/${pkg}_*.deb &>/dev/null; then
        echo "  [skip] $pkg (already downloaded)"
        return 0
    fi

    echo "  [downloading] $pkg..."
    # Try termux.org package repository
    local url="https://packages.termux.dev/apt/termux-main/pool/main/${pkg:0:1}/${pkg}/"

    # Fetch the package listing page to find the latest version
    local latest
    latest=$(curl -sfL "$url" 2>/dev/null | grep -oP "href=\"\K${pkg}_[^\"]+_aarch64\.deb" | sort -V | tail -1)

    if [ -z "$latest" ]; then
        echo "  [warn] Could not find $pkg online. Skipping."
        return 1
    fi

    curl -fSL --retry 3 -o "$DEBS_DIR/$latest" "${url}${latest}" 2>/dev/null && \
        echo "  [ok] $latest ($(wc -c < "$DEBS_DIR/$latest" | tr -d ' ') bytes)" || \
        echo "  [fail] $pkg download failed"
}

echo ""
echo "Downloading Termux .deb packages..."

# Core packages (required for bootstrap)
download_deb "c-ares"
download_deb "libicu"
download_deb "libsqlite"
download_deb "nodejs-lts"
download_deb "npm"
download_deb "git"
download_deb "curl"
download_deb "libcurl"
download_deb "libnghttp2"
download_deb "libnghttp3"
download_deb "libssh2"
download_deb "openssl"
download_deb "ca-certificates"
download_deb "python"
download_deb "python-pip"
download_deb "proot"
download_deb "libtalloc"
download_deb "termux-tools"
download_deb "bash"
download_deb "coreutils"
download_deb "findutils"
download_deb "grep"
download_deb "sed"
download_deb "gawk"
download_deb "tar"
download_deb "gzip"
download_deb "bzip2"
download_deb "xz-utils"
download_deb "unzip"
download_deb "procps"
download_deb "psmisc"
download_deb "less"
download_deb "nano"
download_deb "vim"
download_deb "openssh"
download_deb "rsync"
download_deb "wget"
download_deb "libandroid-glob"
download_deb "libandroid-support"
download_deb "libandroid-shmem"
download_deb "libcap"
download_deb "libexpat"
download_deb "libffi"
download_deb "libgcrypt"
download_deb "libgmp"
download_deb "libgnutls"
download_deb "libgpg-error"
download_deb "libidn2"
download_deb "liblzma"
download_deb "libmpfr"
download_deb "libnettle"
download_deb "libunistring"
download_deb "readline"
download_deb "zlib"

# ── Clone Hermes Agent ────────────────────────────────────────────────────
echo ""
echo "Cloning Hermes Agent source..."

if [ -d "$HERMES_DIR/.git" ]; then
    echo "  [skip] Hermes Agent already cloned. Run: rm -rf $HERMES_DIR to re-clone."
else
    rm -rf "$HERMES_DIR"
    if git clone --depth 1 https://github.com/nousresearch/hermes-agent.git "$HERMES_DIR" 2>/dev/null; then
        echo "  [ok] Hermes Agent cloned ($(du -sh "$HERMES_DIR" | cut -f1))"
    else
        echo "  [warn] Could not clone Hermes Agent. It will be downloaded on first launch instead."
    fi
fi

# ── Summary ───────────────────────────────────────────────────────────────
echo ""
echo "=== Complete Flavor Assets Summary ==="
echo "DEBs: $(ls "$DEBS_DIR"/*.deb 2>/dev/null | wc -l | tr -d ' ') files, $(du -sh "$DEBS_DIR" 2>/dev/null | cut -f1)"
echo "Hermes: $(if [ -d "$HERMES_DIR/.git" ]; then du -sh "$HERMES_DIR" 2>/dev/null | cut -f1; else echo 'not cloned'; fi)"
echo ""
echo "Ready to build complete APK:"
echo "  ./gradlew assembleCompleteDebug"
