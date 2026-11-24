package com.subzero.teslawaze

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional convenience: when armed by the app, this accessibility service taps Android Auto's
 * overflow (three-dot) menu and then "Start head unit server", so starting a session is one tap.
 *
 * Android Auto exposes no API or intent to start its head unit server, so screen automation is the
 * only route. The user enables this service by hand in Accessibility settings; it is scoped to the
 * Android Auto package and only acts while [armed]. It is best-effort and depends on Android Auto's
 * current menu layout and labels, so it may need updating if Google changes that UI.
 */
class HeadUnitStarter : AccessibilityService() {

    companion object {
        /** The app sets this true right before opening Android Auto; we clear it once we've clicked. */
        @Volatile var armed = false
        /** True while the service is enabled and connected, so the app knows automation is available. */
        @Volatile var running = false

        private const val AA = "com.google.android.projection.gearhead"
        // The system's overflow button describes itself as "More options", localized by OS language.
        private val OVERFLOW = listOf("more options", "עוד אפשרויות", "المزيد من الخيارات", "weitere optionen")
    }

    override fun onServiceConnected() {
        running = true
        AppLog.add("אוטומציית Android Auto מוכנה")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private var lastOverflowAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed || event?.packageName != AA) return
        // 1) If the menu item is already on screen, click it and we're done.
        if (clickStartItem()) {
            armed = false
            AppLog.add("הופעל שרת יחידת ראש אוטומטית")
            return
        }
        // 2) Otherwise open the overflow menu that holds it (not too often).
        val now = System.currentTimeMillis()
        if (now - lastOverflowAt > 1200 && clickOverflow()) lastOverflowAt = now
    }

    /** Looks across all current windows for a "Start head unit server" entry and clicks it. */
    private fun clickStartItem(): Boolean {
        for (root in roots()) {
            val match = find(root) { n ->
                val s = ((n.text ?: "").toString() + " " + (n.contentDescription ?: "")).lowercase()
                (s.contains("head unit server") || s.contains("יחידת ראש")) &&
                    !s.contains("stop") && !s.contains("עצור") && !s.contains("הפסק")
            }
            if (match != null && clickUp(match)) return true
        }
        return false
    }

    private fun clickOverflow(): Boolean {
        for (root in roots()) {
            val btn = find(root) { n ->
                n.isClickable && n.contentDescription != null &&
                    OVERFLOW.any { n.contentDescription.toString().lowercase().contains(it) }
            }
            if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { out.add(it) }
        try { for (w in windows) w.root?.let { out.add(it) } } catch (_: Exception) {}
        return out
    }

    /** First node (depth-first) matching [pred], or null. */
    private fun find(node: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (try { pred(node) } catch (_: Exception) { false }) return node
        for (i in 0 until node.childCount) {
            val hit = find(node.getChild(i), pred)
            if (hit != null) return hit
        }
        return null
    }

    /** Clicks the node or the nearest clickable ancestor (menu text is often inside a clickable row). */
    private fun clickUp(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return false
    }
}
