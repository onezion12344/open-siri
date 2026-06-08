# Open Siri for Android — Project Reference

## Architecture
- Kotlin + Jetpack Compose + Material 3
- Embedded Linux VM via Termux bootstrap + proot
- Multi-agent: Hermes (default), Codex, OpenClaw, Claude Code
- Accessibility Service for GUI automation (no root)
- AlarmManager cron daemon with boot persistence
- Two flavors: bootstrap (~39MB, network) and complete (~225MB, offline)

## Key Files

### Compose UI (Bootstrap)
- `app/.../MainActivity.kt` — entry point, agent dashboard
- `app/.../ui/ChainProgressScreen.kt` — chain progress install UX (516 lines)
- `app/.../ui/InstallViewModel.kt` — install phase state machine
- `app/.../ui/theme/Theme.kt` — Material 3 dark theme
- `app/.../service/InstallService.kt` — foreground service for install

### Agent Runtime
- `app/.../AgentServerManager.kt` — multi-agent engine (Hermes/Codex/OpenClaw/ClaudeCode), 1553 lines
- `app/.../AgentAccessibilityService.kt` — screen reading + GUI automation via Accessibility API
- `app/.../AgentForegroundService.kt` — persistent background daemon with notification
- `app/.../CronWakeReceiver.kt` — AlarmManager cron scheduling + BOOT_COMPLETED
- `app/.../StorageBridge.kt` — Android ↔ proot file system bridge, SAF support
- `app/.../BootstrapInstaller.kt` — Termux bootstrap extraction + apt configuration

### Assets & Config
- `app/src/main/assets/hermes_install.sh` — POSIX-dash Hermes install script (6 phases + step/progress output)
- `app/src/main/assets/bootstrap-aarch64.zip` — Termux bootstrap (~29MB)
- `app/src/main/res/xml/accessibility_service_config.xml` — Accessibility service config

### Scripts
- `scripts/download-bootstrap.sh` — Download Termux bootstrap zip
- `scripts/prebuild-complete.sh` — Download .deb packages + Hermes repo for offline flavor

### CI/CD
- `.github/workflows/build-and-release.yml` — Build + GitHub Release on tags
- `.github/workflows/watcher.yml` — /6h upstream release scanner
- `.github/workflows/auto-fix.yml` — Auto-build on bug label
- `.github/workflows/auto-merge.yml` — Auto-merge safe bot PRs (<200 diff lines)

## Install Flow (6 Phases)
Phase data parsed from stdout lines `[phase:N] description`, `[step:N.M] description`, `[progress:N/total] description`:
- 0: Checking environment (0.5 MB)
- 1: Installing dependencies — git, nodejs-lts, npm (61 MB)
- 2: Downloading Hermes Agent — git clone (8 MB)
- 3: Installing packages — npm install (85 MB)
- 4: Building — TypeScript compile + link (2 MB)
- 5: Verifying installation — health checks (0.1 MB)

## Constraints
- minSdk 26, targetSdk 28, compileSdk 35
- **targetSdk=28 is CRITICAL** — bypasses SELinux W^X for proot binary execution. Do NOT raise.
- Package: `com.opensiri.agent.bootstrap`
- Shell scripts: POSIX dash only — no bash-isms. Test with `dash -n`.
- JDK 17 required (Gradle 8.x incompatible with JDK 26)
- Asset .deb files must go in flavor-specific dirs, NOT `app/src/main/assets/`

## Build
```bash
cd ~/onezion-agent
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home

# Bootstrap flavor (~39MB)
./scripts/download-bootstrap.sh
./gradlew assembleBootstrapDebug

# Complete flavor (~225MB, requires prebuild)
./scripts/prebuild-complete.sh
./gradlew assembleCompleteDebug
```

## Testing
```bash
adb install -r app/build/outputs/apk/bootstrap/debug/app-bootstrap-debug.apk
```
