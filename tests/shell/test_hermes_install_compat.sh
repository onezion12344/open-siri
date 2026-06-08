#!/bin/sh
# Test suite for hermes_install.sh — dash compatibility
# Catches bash-isms that break Android init scripts

set -e

SCRIPT="$1"
[ -z "$SCRIPT" ] && { echo "usage: $0 <hermes_install.sh>"; exit 2; }
[ -f "$SCRIPT" ] || { echo "FAIL: $SCRIPT not found"; exit 2; }

PASS=0
FAIL=0

assert() {
    desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        echo "  ✓ $desc"
        PASS=$((PASS+1))
    else
        echo "  ✗ $desc"
        FAIL=$((FAIL+1))
    fi
}

assert_grep_clean() {
    desc="$1"; pattern="$2"
    if ! grep -qE "$pattern" "$SCRIPT"; then
        echo "  ✓ $desc (no match: $pattern)"
        PASS=$((PASS+1))
    else
        echo "  ✗ $desc (found bash-ism: $pattern)"
        grep -nE "$pattern" "$SCRIPT" | sed 's/^/      /'
        FAIL=$((FAIL+1))
    fi
}

echo "═══ hermes_install.sh dash compatibility tests ═══"
echo ""

echo "── 1. Syntax check ──"
assert "parses under dash" dash -n "$SCRIPT"
assert "parses under bash" bash -n "$SCRIPT"
echo ""

echo "── 2. No bash-only builtins/options ──"
# These are the bugs we've hit before — must stay clean
assert_grep_clean "no 'set -o pipefail'" "set -o pipefail"
assert_grep_clean "no '[[ ]]' conditional" '\[\[ '
assert_grep_clean "no '==' in tests" '^\s*[^=!<>]*\s+==\s+'
assert_grep_clean "no '&>' redirection" '&>'
assert_grep_clean "no 'source' builtin" '^\s*source\s+'
assert_grep_clean "no 'local' keyword" '^\s*local\s+'
assert_grep_clean "no 'declare'" '^\s*declare\s'
assert_grep_clean "no arrays" '\(\s*[a-zA-Z_]+='
echo ""

echo "── 3. POSIX exit semantics ──"
# dash doesn't have 'exit' inside if-blocks working the same way; we use return in funcs
if grep -qE '^\s*exit\s+[0-9]+\s*$' "$SCRIPT"; then
    echo "  ⚠ top-level 'exit N' present — OK for entrypoint, but verify it's outside if-block"
    EXIT_LINES=$(grep -nE '^\s*exit\s+[0-9]+\s*$' "$SCRIPT" | wc -l | tr -d ' ')
    echo "    found $EXIT_LINES top-level exit(s)"
fi
# "exit N" inside an if-block is the real bug. Detect: line starts with "if", ends with "; then" → look at next non-blank, non-fi line for "exit"
if awk '
    /^\s*if\b.*;\s*then\s*$/ { in_if=1; next }
    in_if && /^\s*fi\s*$/ { in_if=0; next }
    in_if && /^\s*exit\s+[0-9]+\s*$/ { print FILENAME":"NR": "$0; bad=1 }
    END { exit bad }
' "$SCRIPT"; then
    echo "  ✓ no 'exit' inside if/fi blocks"
    PASS=$((PASS+1))
else
    echo "  ✗ found 'exit' inside if/fi block"
    FAIL=$((FAIL+1))
fi
echo ""

echo "── 4. Required functions present ──"
for fn in phase step progress log ensure_cmd download_with_retry; do
    if grep -qE "^${fn}\s*\(\s*\)" "$SCRIPT"; then
        echo "  ✓ function $fn() defined"
        PASS=$((PASS+1))
    else
        echo "  ✗ function $fn() missing"
        FAIL=$((FAIL+1))
    fi
done
echo ""

echo "── 5. Phase markers (for ChainProgress UI) ──"
# UI reads these — must be present
for marker in '\[phase:' '\[step:' '\[progress:'; do
    if grep -q "$marker" "$SCRIPT"; then
        echo "  ✓ emits $marker markers"
        PASS=$((PASS+1))
    else
        echo "  ✗ missing $marker markers (UI won't render progress)"
        FAIL=$((FAIL+1))
    fi
done
echo ""

echo "── 6. Heredoc/quoting safety ──"
if grep -qE '<<-[A-Z]+' "$SCRIPT"; then
    echo "  ⚠ '<<-' indented heredoc present — must use <<- if any"
fi
# Real bug is a literal "$" followed by text inside double quotes (not ${VAR} expansion).
# Match: "X$Y where Y is not { ( would be ${...} expansion) and not a special param.
if grep -E '"[^"]*\$[A-Za-z_][A-Za-z0-9_]*[^}"]' "$SCRIPT" >/dev/null 2>&1; then
    echo "  ⚠ Possible unescaped \$VAR (manual check needed):"
    grep -nE '"[^"]*\$[A-Za-z_][A-Za-z0-9_]*[^}"]' "$SCRIPT" | sed 's/^/      /'
    echo "  → If those are intentional, ignore. Otherwise wrap with \${...} or escape with \\$"
    PASS=$((PASS+1))  # warn, not fail
else
    echo "  ✓ no unescaped \$VAR in double quotes"
    PASS=$((PASS+1))
fi
echo ""

echo "── 7. Smoke run with stubbed env ──"
TMP=$(mktemp -d)
cat > "$TMP/pkg" <<'EOF'
#!/bin/sh
echo "fake pkg: $*"
EOF
cat > "$TMP/unzip" <<'EOF'
#!/bin/sh
echo "fake unzip: $*"
EOF
chmod +x "$TMP/pkg" "$TMP/unzip"

# Stub: just source the script's function defs, don't actually run
cat > "$TMP/runner.sh" <<EOF
#!/bin/sh
export HERMES_HOME="$TMP/home"
export USR_DIR="$TMP/usr"
export PATH="$TMP:\$PATH"
mkdir -p "\$HERMES_HOME" "\$USR_DIR"
# Source defs only
sed -n '/^# ── Check command/,/^# ── Download with retry/p' "$SCRIPT" > "$TMP/defs.sh"
. "$TMP/defs.sh"
# Try calling one
ensure_cmd "fake-pkg" "fake-pkg"
echo "OK"
EOF
chmod +x "$TMP/runner.sh"

if "$TMP/runner.sh" >/dev/null 2>&1; then
    echo "  ✓ defs source + ensure_cmd runs"
    PASS=$((PASS+1))
else
    echo "  ✗ defs source failed"
    FAIL=$((FAIL+1))
fi
rm -rf "$TMP"
echo ""

echo "══════════════════════════════════════════════════"
echo "  PASS: $PASS  FAIL: $FAIL"
echo "══════════════════════════════════════════════════"
[ "$FAIL" -eq 0 ] || exit 1
