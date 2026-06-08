# Open Siri Test Loop — /loop 30m

Run every 30 minutes: full build + shell audit + ADB smoke test. Reports to Telegram on failure only.

## Loop Command

```bash
/loop 30m /open-siri-test
```

## What it does

1. **Build APK (bootstrap flavor)** — runs `./gradlew assembleBootstrapDebug`, reports compile errors
2. **Shell script audit** — runs `dash -n` on all `.sh` files in `app/src/main/assets/` and `scripts/`, plus bash-ism scan using `checkbashisms`
3. **ADB smoke test** — if device connected: install APK, launch app, wait 10s, check logcat for crashes
4. **Telegram notification** — only on failure, posts to chat 7580128132 with error summary

## Implementation

When this command runs, Claude Code should:

```
1. Build:
   cd ~/onezion-agent
   export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
   export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
   ./gradlew assembleBootstrapDebug --quiet 2>&1
   → Record: BUILD SUCCESSFUL or BUILD FAILED

2. Shell Audit:
   for f in app/src/main/assets/*.sh scripts/*.sh; do
     dash -n "$f" 2>&1 || echo "SYNTAX ERROR: $f"
     grep -n 'pipefail\|\[\[ \|== \|function \|&>' "$f" && echo "BASH-ISM: $f"
   done
   → Record: CLEAN or errors found

3. ADB Smoke (skip if no device):
   DEVICE=$(adb devices 2>/dev/null | grep -v "List" | grep "device$" | head -1)
   if [ -n "$DEVICE" ]; then
     adb install -r app/build/outputs/apk/bootstrap/debug/app-bootstrap-debug.apk
     adb shell am start -n com.opensiri.agent.bootstrap.bootstrap/.MainActivity
     sleep 10
     adb logcat -d | grep -i "AndroidRuntime\|FATAL\|crash" | tail -5
   fi
   → Record: PASSED or FAILED or SKIPPED (no device)

4. Report (only on failure):
   If any step failed, send Telegram message via Bot API:
   curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
     -d chat_id=7580128132 \
     -d text="🔴 Open Siri test failure:
   • Build: $BUILD_STATUS
   • Shell: $SHELL_STATUS
   • Smoke: $SMOKE_STATUS
   $(cat /tmp/osiri-test-errors.txt)"
```

## Constraints
- Quiet on success — no notification means everything is fine
- Max 5 min runtime per tick
- ADB step is optional (skip if no device connected)
- Use TELEGRAM_BOT_TOKEN from ~/.hermes/.env
