package com.jarvis.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** The "hands and eyes" of Jarvis: reads the screen and performs taps/scrolls/typing in any app. */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        val isOn get() = instance != null
        @Volatile var currentPackage: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Accessibility-bound apps may start the wake-word service from background.
        AssistantService.startHotwordIfEnabled(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { currentPackage = it.toString() }
            // v1.4.2: WhatsApp incoming call asle auto-answer (switch ON thakle)
            if (currentPackage == "com.whatsapp" || currentPackage == "com.whatsapp.w4b") {
                com.jarvis.ai.tools.WhatsAppCall.maybeAutoAnswer(this)
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm); dm.widthPixels to dm.heightPixels
        }
    }

    // ---------- reading the screen ----------
    data class UiNode(val text: String, val x: Int, val y: Int, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean, val id: String)

    fun dumpScreen(limit: Int = 180): List<UiNode> {
        val out = mutableListOf<UiNode>()
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
        for (r in roots) walk(r, out, limit)
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<UiNode>, limit: Int) {
        if (n == null || out.size >= limit) return
        if (n.isVisibleToUser) {
            val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(),
                if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null)
                .filter { it.isNotBlank() }.distinct().joinToString(" | ")
            if (label.isNotBlank() || n.isEditable || (n.isClickable && n.viewIdResourceName != null) || n.isScrollable) {
                val r = Rect(); n.getBoundsInScreen(r)
                out.add(UiNode(label.take(120), r.centerX(), r.centerY(), n.isClickable, n.isEditable, n.isScrollable,
                    n.viewIdResourceName?.substringAfter(":id/") ?: ""))
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, limit)
    }

    fun screenText(): String {
        val (w, h) = screenSize()
        val sb = StringBuilder("App: $currentPackage  Screen: ${w}x$h\n")
        dumpScreen().forEach {
            val flags = buildString {
                if (it.clickable) append("[btn]"); if (it.editable) append("[input]"); if (it.scrollable) append("[scroll]")
            }
            sb.append("• \"${it.text}\" $flags @(${it.x},${it.y})${if (it.id.isNotEmpty()) " id=${it.id}" else ""}\n")
        }
        return sb.toString()
    }

    /** Screenshot (Android 11+) without any extra prompt. Returns JPEG bytes. */
    suspend fun screenshotJpeg(maxSide: Int = 1024): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return ScreenCapture.latestJpeg(maxSide)
        return withTimeoutOrNull(4000) { takeShot(maxSide) } ?: ScreenCapture.latestJpeg(maxSide)
    }

    @RequiresApi(30)
    private suspend fun takeShot(maxSide: Int): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (cont.isActive) cont.resume(bmp?.let { ScreenCapture.toJpeg(it, maxSide) })
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }

    // ---------- acting ----------
    // v1.4.0: precise clicks — candidates are SCORED, not just substring-matched:
    // exact text (0) > exact description (1) > starts-with text (2) > starts-with
    // description (3) > contains text (4) > contains description (5) > view id (6).
    // "Install" therefore never hits "Uninstall". Clickable nodes and short
    // texts rank higher. Only visible nodes are considered.
    private fun findNodes(query: String): List<AccessibilityNodeInfo> {
        val q = query.lowercase().trim()
        val res = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(rootInActiveWindow) }
        fun score(n: AccessibilityNodeInfo): Int? {
            if (!n.isVisibleToUser) return null
            val text = n.text?.toString()?.trim() ?: ""
            val desc = n.contentDescription?.toString()?.trim() ?: ""
            val id = n.viewIdResourceName ?: ""
            val tl = text.lowercase(); val dl = desc.lowercase()
            val base = when {
                tl == q -> 0
                dl == q -> 1
                tl.startsWith(q) -> 2
                dl.startsWith(q) -> 3
                tl.contains(q) -> 4
                dl.contains(q) -> 5
                id.lowercase().contains(q) -> 6
                else -> return null
            }
            val clickBonus = if (n.isClickable || n.isLongClickable) 0 else 10
            val lenPenalty = ((text.length + desc.length - q.length).coerceAtLeast(0)) / 10
            return base * 20 + clickBonus + lenPenalty
        }
        fun w(n: AccessibilityNodeInfo?) {
            if (n == null) return
            score(n)?.let { res.add(n to it) }
            for (i in 0 until n.childCount) w(n.getChild(i))
        }
        roots.forEach { w(it) }
        return res.sortedBy { it.second }.map { it.first }
    }

    // v1.4.0: debounce — the same button is never tapped twice within 400ms.
    @Volatile private var lastClickQuery = ""
    @Volatile private var lastClickAt = 0L

    /**
     * v1.3.2: answer an incoming call by tapping the phone app's Answer button.
     * TelecomManager.acceptRingingCall() only works when Jarvis is the default
     * dialer, so on most phones this accessibility tap is what actually answers.
     * allowSwipe adds a slide-to-answer swipe (only when the call is still ringing).
     */
    fun answerIncomingCall(allowSwipe: Boolean = false): Boolean {
        for (c in listOf("Answer", "উত্তর", "Receive", "Accept", "Swipe up to answer", "Slide to answer")) {
            if (clickText(c)) return true
        }
        if (allowSwipe) {
            val (w, h) = screenSize()
            if (swipe(w / 2f, h * 0.85f, w / 2f, h * 0.35f, 400)) return true
        }
        return false
    }

    /**
     * v1.4.0: precise clicks — tries the ranked candidates in order (up to 3),
     * and VERIFIES after each tap: the screen text must change, or the target
     * must disappear. If nothing changed, the tap did not work — try the next
     * candidate instead of assuming success. The same button is never tapped
     * twice within 400ms (debounce). Returns true only on a verified tap.
     */
    fun clickText(query: String, longPress: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (query.equals(lastClickQuery, true) && now - lastClickAt < 400) return true
        val before = screenText()
        for (n in findNodes(query).take(3)) {
            var done = false
            var c: AccessibilityNodeInfo? = n
            while (c != null) {
                if (c.isClickable || c.isLongClickable) {
                    done = c.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                c = c.parent
            }
            if (!done) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    done = tap(r.centerX().toFloat(), r.centerY().toFloat(), if (longPress) 700 else 60)
                }
            }
            if (done) {
                Thread.sleep(350)
                if (screenText() != before || findNodes(query).isEmpty()) {
                    lastClickQuery = query; lastClickAt = System.currentTimeMillis()
                    return true
                }
            }
        }
        return false
    }

    fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build(), null, null)
    }

    fun scroll(direction: String): Boolean {
        val (w, h) = screenSize()
        val cx = w / 2f; val cy = h / 2f
        return when (direction.lowercase()) {
            "up" -> swipe(cx, h * 0.3f, cx, h * 0.75f)        // see content above
            "left" -> swipe(w * 0.2f, cy, w * 0.85f, cy)
            "right" -> swipe(w * 0.85f, cy, w * 0.15f, cy)
            else -> swipe(cx, h * 0.75f, cx, h * 0.25f)        // down = next post / video (reels, shorts)
        }
    }

    fun typeText(text: String, append: Boolean = false): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(rootInActiveWindow) ?: return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val finalText = if (append) (focused.text?.toString() ?: "") + text else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    fun pressEnter(): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }

    fun global(action: String): Boolean {
        val a = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_menu", "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock", "lock_screen", "screen_off" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return false
        }
        return performGlobalAction(a)
    }

    /** Clicks a WhatsApp/Telegram style send button on the current screen. */
    fun clickSend(): Boolean {
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        val root = rootInActiveWindow ?: return false
        for (id in ids) root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        return clickText("Send") || clickText("পাঠান") || clickText("भेजें")
    }
}
