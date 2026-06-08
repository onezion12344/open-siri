# Open Siri Bootstrap — Android Project

## Architecture
- Kotlin + Jetpack Compose + Material 3
- Single Activity, multi-screen via Compose navigation
- Foreground Service for background install
- Assets: hermes_install.sh (already in app/src/main/assets/)

## Key Files
- `app/src/main/java/com/opensiri/agent/bootstrap/MainActivity.kt` — entry point
- `app/src/main/java/com/opensiri/agent/bootstrap/ui/ChainProgressScreen.kt` — chain progress UX
- `app/src/main/java/com/opensiri/agent/bootstrap/ui/InstallViewModel.kt` — state machine
- `app/src/main/java/com/opensiri/agent/bootstrap/service/InstallService.kt` — foreground service
- `app/src/main/java/com/opensiri/agent/bootstrap/ui/theme/Theme.kt` — Material 3 theme

## UX Requirements (Chain Progress)
The install screen MUST implement:
1. **Chain of connected phase nodes** — 6 phases, vertical timeline with connecting line
2. **Phase states**: completed (green ✓), active (purple pulsing ◉), pending (gray ○)
3. **Active phase expands** to show sub-steps with individual status
4. **Top progress bar** with MB downloaded / total
5. **Each phase shows**: icon, name, description, badge, file sizes
6. **Collapsible log viewer** at bottom
7. **Retry button** appears on failure
8. Dark theme Material 3 (#1A1721 background, #6750A4 primary, #E8DEF8 on-surface)

Reference: the HTML at /tmp/opensiri_chain_ux.html shows the exact desired behavior.

## Install Flow (6 phases)
Phase data is read from the install script's stdout lines matching `[phase:N] description`:
- 0: Checking environment (4.2 MB)
- 1: Installing dependencies — git, nodejs-lts, npm (61 MB)
- 2: Downloading Hermes Agent — git clone (8 MB)
- 3: Installing packages — npm install (85 MB)
- 4: Building — TypeScript compile + link
- 5: Verifying installation — health checks

## Constraints
- minSdk 26, targetSdk 28, compileSdk 35
- targetSdk=28 is CRITICAL — allows executing binaries from app data directory (SELinux W^X bypass). Do NOT raise above 28.
- Package: com.opensiri.agent.bootstrap
- No network libraries needed — the install runs in Termux shell
- Script runs via ProcessBuilder, output parsed line by line
- Adaptive icon (already prepared, reference in res/mipmap-anydpi-v26)

## Build
```bash
cd ~/onezion-agent
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew assembleDebug
```
Output APK: app/build/outputs/apk/debug/app-debug.apk

## Testing
After build:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
