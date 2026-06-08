# Open Siri for Android — Project Constitution

## Mission
Open Siri is an Android APK that runs AI agents (Hermes, Claude Code, Codex, OpenClaw) inside an embedded Linux VM via proot. It provides cron scheduling, Accessibility-based GUI automation, file system access, and a Material 3 WebView GUI — enabling autonomous AI agent capabilities on Android without root.

## Architecture Principles

1. **No root required** — Everything works via Accessibility Service + proot
2. **Multi-agent** — Hermes (default), Claude Code, Codex, OpenClaw — switchable at runtime
3. **Offline-capable** — Two build flavors: bootstrap (downloads deps) and complete (pre-bundled)
4. **POSIX shell only** — All shell scripts must run under Termux dash/sh, NOT bash
5. **targetSdk=28** — Required to bypass SELinux W^X for proot binary execution (side-load only)

## Non-Negotiables

- **Shell scripts**: No bash-isms. Test with `dash -n` and actual `dash` execution.
- **`set -e` not `set -o pipefail`**: pipefail doesn't work in dash.
- **`exit` never inside `if`/`else`/`while`**: POSIX `set -e` silently ignores `exit` in conditionals. Use flag variables.
- **Asset duplication**: `.deb` files go in flavor-specific assets (`app/src/complete/assets/`), NOT `app/src/main/assets/`.
- **JDK 17**: Gradle 8.x is incompatible with JDK 26.

## Key Files

| File | Purpose |
|------|---------|
| `app/.../MainActivity.kt` | Entry point, Compose UI |
| `app/.../ui/ChainProgressScreen.kt` | Chain progress install UX (516 lines) |
| `app/.../ui/InstallViewModel.kt` | Install phase state machine |
| `app/.../service/InstallService.kt` | Foreground service for background install |
| `app/src/main/assets/hermes_install.sh` | POSIX-dash Hermes install script |
| `scripts/download-bootstrap.sh` | Fetch Termux bootstrap zip |
| `.github/workflows/` | CI/CD: build + watcher + auto-fix + auto-merge |

## Install Flow (6 Phases)
0. Checking environment (tools: curl, tar)
1. Installing system dependencies (git, nodejs-lts, npm — ~61 MB)
2. Downloading Hermes Agent source (git clone — ~8 MB)
3. Installing npm packages (~85 MB)
4. Building TypeScript + linking CLI
5. Verifying installation

## References
- `WIKI.md` — Detailed project wiki
- `CLAUDE.md` — Claude Code project instructions
- `~/.hermes/skills/software-development/open-siri/SKILL.md` — Build & maintain guide
- `~/.hermes/skills/autonomous-ai-agents/open-siri-android/SKILL.md` — Architecture & multi-agent pipeline
