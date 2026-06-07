# Open Siri — Project Wiki

## Overview

**Open Siri** — an Android APK that puts a full AI agent (Hermes Agent) directly on-phone. Open-source, self-hosted alternative to Apple's 2026 Siri, with cron scheduling, local file access, and Material 3 GUI.

Two editions:

| Edition | Size | Description |
|---------|------|-------------|
| **Open Siri** (Complete) | 225 MB | Full Termux + Hermes Agent embedded, zero-download install |
| **Open Siri Bootstrapper** | 8.4 MB | Lightweight; downloads dependencies on first launch |

- **Package:** com.opensiri.agent.bootstrap
- **Platform:** Android 8+ (API 26), Kotlin + Jetpack Compose
- **Agent Engine:** Hermes Agent (nousresearch/hermes-agent)
- **Source:** ~/onezion-agent/
- **APKs:** ~/Downloads/OpenSiri-Complete-v1.apk (~/Downloads/OpenSiri-v0.2.0.apk)

## Architecture

```
┌─────────────────────────────────────────┐
│ Open Siri APK (8.4 MB)                  │
│ ┌─────────────────────────────────────┐ │
│ │ Chain Progress UI (Compose M3)      │ │
│ │ 6-phase chained installer           │ │
│ └──────────────┬──────────────────────┘ │
│ ┌──────────────▼──────────────────────┐ │
│ │ InstallViewModel (State Machine)    │ │
│ │ Parses [phase:N] from script stdout │ │
│ └──────────────┬──────────────────────┘ │
│ ┌──────────────▼──────────────────────┐ │
│ │ hermes_install.sh (181 lines)       │ │
│ │ POSIX sh (dash-compatible)          │ │
│ │ • pkg install git nodejs-lts        │ │
│ │ • git clone hermes-agent            │ │
│ │ • npm install + build + link        │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## Install Phases (Chain Progress)

| # | Phase | Size | Key Actions |
|---|-------|------|-------------|
| 0 | 🔍 Checking environment | 4.2 MB | Termux bootstrap, filesystem, PATH |
| 1 | 📦 Installing dependencies | 61 MB | git (12.3 MB), nodejs-lts (48.7 MB) |
| 2 | ⬇️ Downloading Hermes Agent | 8 MB | git clone --depth 1, 3-retry logic |
| 3 | 📥 Installing packages | 85 MB | npm install --production |
| 4 | 🔧 Building | — | TypeScript compile, CLI link |
| 5 | ✅ Verifying | — | hermes --version, health checks |
| **Total** | | **~158 MB** | |

## Key Design Decisions

### Shell Compatibility (POSIX / dash)
Termux bootstrap uses dash, NOT bash. The install script had 3 bash-isms that caused failures:

| Bug | Cause | Fix |
|-----|-------|-----|
| ❌ `set -o pipefail` | bash-only | ✅ `set -e` |
| ❌ `exit 1` inside if/else | POSIX `set -e` suppresses exits in conditionals | ✅ flag variables, exit outside if-blocks |
| ❌ git not found | Termux bootstrap has no git | ✅ `pkg install -y git` before clone |

All verified with: `sh -n` ✅, `dash -n` ✅, bash-ism scan ✅

### Chain Progress UX
- Vertical timeline with connected phase nodes
- 3 states per node: green ✓ (done), purple pulsing ◉ (active), gray ○ (pending)
- Active phase expands to show sub-steps with file sizes
- Collapsible terminal log viewer (monospace, color-coded)
- Top progress bar: MB downloaded / total
- Material 3 dark theme (#1A1721 background, #6750A4 primary)

Inspired by: NNGroup onboarding patterns, Play Store feature-delivery UX, wizard design pattern

### Claude Code Integration
Project configured for Claude Code via DeepSeek Anthropic-compatible endpoint:
- Base URL: api.deepseek.com/anthropic
- Models: deepseek-v4-pro (Sonnet/Opus), deepseek-v4-flash (Haiku)
- Settings: ~/.claude/settings.json

## Build Instructions

```bash
cd ~/onezion-agent
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:**
- Android SDK 35, Build Tools 35.0.0
- Java 17 (Java 26+ incompatible with Kotlin 2.1.0 compiler)
- Gradle 8.10 (wrapper included)

## File Map

```
~/onezion-agent/
├── CLAUDE.md                    # Project context for Claude Code
├── WIKI.md                      # This document
├── build.gradle.kts             # AGP 8.7.3, Kotlin 2.1.0, Compose BOM
├── settings.gradle.kts
├── gradle.properties            # Java 17 path pin
├── gradlew / gradle/wrapper/
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/hermes_install.sh     # 181 lines, POSIX sh
        ├── java/com/opensiri/agent/bootstrap/
        │   ├── MainActivity.kt          # Entry point (20 lines)
        │   ├── ui/
        │   │   ├── ChainProgressScreen.kt  # Chain UX (516 lines)
        │   │   ├── InstallViewModel.kt     # State machine (213 lines)
        │   │   └── theme/Theme.kt          # M3 dark theme (37 lines)
        │   └── service/
        │       └── InstallService.kt       # Foreground service (62 lines)
        └── res/                           # Colors, themes, adaptive icon
```

## Future Ideas

- [ ] "Yellow Sheep" branding variant
- [ ] Termux API integration (SMS, notifications, sensors)
- [ ] Background cron daemon for scheduled agent tasks
- [ ] Voice-to-text integration (whisper.cpp on-device)
- [ ] Telegram bot bridge (remote control)
- [ ] Skill marketplace (downloadable agent skills)

## Sessions & Changelog

| Date | What |
|------|------|
| 2026-06-07 | v0.2.0: Chain progress UX, fixed install script, renamed to Open Siri |
| 2026-06-07 | v0.1.0: Initial bootstrap APK with basic install UI |

---

*Built with Hermes Agent + Claude Code + Gradle. Project source: ~/onezion-agent/*