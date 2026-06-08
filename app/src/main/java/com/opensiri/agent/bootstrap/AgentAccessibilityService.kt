package com.opensiri.agent.bootstrap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList

/**
 * Android Accessibility Service for Open Siri.
 *
 * Provides screen reading and GUI automation capabilities without root:
 * - Captures window changes, clicks, and text changes via accessibility events
 * - Exposes the accessibility tree as structured text for AI consumption
 * - Performs taps, swipes, and text input via accessibility gestures
 * - Manages app launching and installed-app discovery
 * - Maintains a running buffer of recent events for context
 *
 * Singletons pattern via [AgentAccessibilityService.instance] companion.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11yService"

        /** Maximum number of recent accessibility events to buffer. */
        private const val MAX_EVENT_BUFFER = 200

        /**
         * Singleton instance set in [onServiceConnected] and cleared in [onDestroy].
         * External callers (e.g. CronWakeReceiver, MainActivity) can check this
         * to determine whether the service is running.
         */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /**
         * Check whether the accessibility service is enabled in system settings.
         * Call this from an Activity before attempting to use accessibility features.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colon = enabledServices.split(':')
            return colon.any { it.contains(context.packageName) }
        }

        /**
         * Open the system accessibility settings so the user can enable this service.
         * On Android 13+ (API 33), the settings panel may include additional
         * privacy notices that the user must acknowledge.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    // ── Event buffer ─────────────────────────────────────────────────────

    /** Thread-safe buffer of the most recent accessibility event descriptions. */
    private val eventBuffer = LinkedList<EventEntry>()

    data class EventEntry(
        val timestamp: Long,
        val eventType: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
    )

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            // Enable gesture support (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val entry = EventEntry(
            timestamp = System.currentTimeMillis(),
            eventType = eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" "),
            contentDescription = event.contentDescription?.toString(),
        )

        synchronized(eventBuffer) {
            eventBuffer.addLast(entry)
            while (eventBuffer.size > MAX_EVENT_BUFFER) {
                eventBuffer.removeFirst()
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                Log.v(TAG, "Event: ${entry.eventType} pkg=${entry.packageName} " +
                        "cls=${entry.className} text=${entry.text?.take(80)}")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        synchronized(eventBuffer) { eventBuffer.clear() }
        Log.i(TAG, "Accessibility service destroyed")
    }

    // ── Screen content ───────────────────────────────────────────────────

    /**
     * Traverse the accessibility tree rooted at the active window and
     * return a structured text representation suitable for AI consumption.
     *
     * The format uses indentation and pipe characters to represent the
     * view hierarchy, with each line containing the view class, text/content
     * description, resource-id, and bounds.
     */
    fun getCurrentScreenContent(): String {
        val root = rootInActiveWindow ?: return "No active window"
        val sb = StringBuilder()
        buildNodeTree(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    /**
     * Recursively walk [node] and append its representation to [sb].
     * Stops at max depth of 20 to avoid runaway recursion.
     */
    private fun buildNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 20) return

        val indent = "  ".repeat(depth.coerceAtMost(10))

        // Get the rect in screen coordinates
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Build a compact description
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(60)?.replace("\n", "\\n") ?: ""
        val cd = node.contentDescription?.toString()?.take(60)?.replace("\n", "\\n") ?: ""
        val resId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val clickable = if (node.isClickable) " [clickable]" else ""
        val checkable = if (node.isCheckable) " [${if (node.isChecked) "✓" else "✗"}]" else ""
        val focused = if (node.isFocused) " [focused]" else ""

        sb.append(indent)
        sb.append("├─ $cls")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (cd.isNotEmpty()) sb.append(" desc=\"$cd\"")
        if (resId.isNotEmpty()) sb.append(" id=$resId")
        sb.append(" ($rect)")
        sb.append(clickable)
        sb.append(checkable)
        sb.append(focused)
        sb.appendLine()

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            buildNodeTree(child, sb, depth + 1)
            child.recycle()
        }
    }

    // ── Recent events ────────────────────────────────────────────────────

    /**
     * Return a snapshot of recent accessibility events for context.
     */
    fun getRecentEvents(maxCount: Int = 20): List<EventEntry> {
        synchronized(eventBuffer) {
            return eventBuffer.takeLast(maxCount.coerceAtMost(MAX_EVENT_BUFFER))
        }
    }

    // ── Gesture actions ──────────────────────────────────────────────────

    /**
     * Perform a tap at screen coordinates (x, y).
     * Returns true if the gesture was dispatched successfully.
     */
    fun performTap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gestures require API 24+")
            return false
        }

        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Perform a swipe from (x1, y1) to (x2, y2).
     * The duration is scaled based on distance (200–800 ms).
     */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gestures require API 24+")
            return false
        }

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val distance = Math.sqrt(
            ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)).toDouble()
        )
        val duration = (distance * 0.5).toLong().coerceIn(200, 800)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Type [text] into the currently focused input field by setting
     * its text via the accessibility ACTION_SET_TEXT action.
     *
     * First finds the focused editable node; if none is focused, searches
     * the entire tree for the first editable field.
     */
    fun performType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Find a focused editable node
        val target = findEditableNode(root)
        root.recycle()

        if (target == null) {
            Log.w(TAG, "No editable node found for typing")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        target.recycle()
        return result
    }

    /**
     * Find the first editable (focused or not) node in the tree.
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    // ── App management ───────────────────────────────────────────────────

    /**
     * Return a list of installed app package names (non-system, launchable).
     */
    fun getInstalledApps(): List<String> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
    }

    /**
     * Launch an app by its package name. Returns true if the launch intent
     * was dispatched.
     */
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.i(TAG, "Launched $packageName")
                true
            } else {
                Log.w(TAG, "No launch intent for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName: ${e.message}")
            false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Convert an [AccessibilityEvent] type constant to a human-readable string.
     */
    private fun eventTypeToString(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "VIEW_ACCESSIBILITY_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "VIEW_ACCESSIBILITY_FOCUS_CLEARED"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "VIEW_TEXT_TRAVERSED"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "GESTURE_DETECTION_START"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "GESTURE_DETECTION_END"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "TOUCH_EXPLORATION_START"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "TOUCH_EXPLORATION_END"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        else -> "UNKNOWN($type)"
    }
}
