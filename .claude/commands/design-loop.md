# Open Siri Design Audit Loop — /loop 24h

Daily check: Material 3 compliance, Compose API freshness, icon quality. Creates GitHub issues for stale design.

## Loop Command

```bash
/loop 24h /open-siri-design-audit
```

## What it does

1. **Check M3 guidelines** — search for new Material Design 3 updates, Compose BOM releases
2. **Audit theme files** — review `colors.xml`, `themes.xml`, `Theme.kt` against latest M3 tokens
3. **Check accessibility** — verify all clickable elements in Compose have contentDescription, all colors meet contrast ratios
4. **Icon assessment** — check if icon was generated >30 days ago, suggest Luma regeneration
5. **GitHub issue** — create a "design update" issue if anything stale, with specific upgrade steps
6. **Telegram summary** — post brief audit result

## Implementation

When this command runs, Claude Code should:

```
1. Compose BOM Check:
   Visit https://developer.android.com/jetpack/compose/bom/bom-mapping
   Extract latest stable BOM version
   Compare with current: grep "compose-bom" app/build.gradle.kts
   → If newer BOM available, flag for update

2. Theme Audit:
   Read app/src/main/res/values/colors.xml
   Read app/src/main/res/values/themes.xml
   Read app/src/main/java/com/opensiri/agent/bootstrap/ui/theme/Theme.kt
   Check against M3 spec:
   - Are we using color roles (primary, surface, error, etc)?
   - Are we using MaterialTheme.colorScheme or hardcoded colors?
   - Do we have dark theme support?
   → Flag issues found

3. Accessibility Check:
   Read ChainProgressScreen.kt and MainActivity.kt
   Check for:
   - Every clickable has contentDescription or semantics
   - Color contrast: primary (#6750A4) on background (#1A1721) = pass?
   - Text sizes >= 12sp
   → Flag issues found

4. Icon Freshness:
   Check res/mipmap-* for icon files
   Check if >30 days since generation (use git log on icon files)
   → If stale, suggest: "Use Luma Uni-1.1 with prompt: [best prompt from SKILL.md]"

5. GitHub Issue:
   If any flags found, create issue titled "Design Audit: [date]" with checklist
   Label: "design"
   Body: checklist of action items with specific file paths and suggested fixes

6. Telegram:
   curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
     -d chat_id=7580128132 \
     -d text="🎨 Open Siri design audit:
   • M3 BOM: [current] → [latest or 'current']
   • Theme compliance: [issues count] issues
   • Accessibility: [issues count] issues
   • Icon: [fresh or '30+ days old — consider regenerating']
   Issue: [link]"

## Constraints
- Non-blocking — design issues don't break builds
- Actionable output — every issue has a file path and suggested fix
- Don't auto-apply changes — just open issues for human review
