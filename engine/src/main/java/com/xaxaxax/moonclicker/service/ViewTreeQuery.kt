package com.xaxaxax.moonclicker.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.app.UiAutomationConnection
import android.app.UiAutomationHidden
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import dev.rikka.tools.refine.Refine
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 顯示器上的視窗與節點樹（#199），以這個行程（shell）自建的 UiAutomation 取得，見
 * `docs/research/view-tree-on-virtual-display.md`。
 *
 * 系統同時只允許一個 UiAutomation，所以只在有人查詢時註冊，閒置 [IDLE_RELEASE_MS] 後釋放，
 * 把位置讓給 instrumentation 測試或 `uiautomator`。註冊帶 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，
 * 不停用使用者的無障礙服務。
 */
internal class ViewTreeQuery {
    private val handler by lazy { Handler(HandlerThread("ViewTreeQuery").apply { start() }.looper) }
    private var automation: UiAutomation? = null
    private val release = Runnable { close() }

    /**
     * [displayId] 上的視窗，最上層的在前，JSON 陣列。欄位見 `docs/lua-api.md` 的 `ui`。
     *
     * @throws UnsupportedOperationException API 30 以下：`getWindowsOnAllDisplays` 是 API 30。
     * @throws IllegalStateException 已有別的 UiAutomation（instrumentation、`uiautomator`）註冊，或查詢失敗。
     */
    @Synchronized
    fun dump(displayId: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("view tree requires Android 11 (API 30)")
        }
        handler.removeCallbacks(release)
        try {
            return JSONArray(windows(displayId).map(::window)).toString()
        } catch (t: Throwable) {
            Timber.w(t, "getViewTree($displayId) failed")
            // 連線可能已經壞了，下次重新註冊。
            close()
            throw t.toBinderException("getViewTree")
        } finally {
            handler.postDelayed(release, IDLE_RELEASE_MS)
        }
    }

    @Synchronized
    fun close() {
        val current = automation ?: return
        automation = null
        runCatching { Refine.unsafeCast<UiAutomationHidden>(current).disconnect() }
            .onFailure { Timber.w(it, "UiAutomation.disconnect failed") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun windows(displayId: Int): List<AccessibilityWindowInfo> {
        automation?.let { return it.windowsOnAllDisplays.get(displayId).orEmpty() }

        val connected = connect()
        automation = connected
        // 剛註冊時系統還沒開始追蹤視窗，前幾次會拿到空的。
        val deadline = SystemClock.uptimeMillis() + FIRST_WINDOWS_TIMEOUT_MS
        while (true) {
            val windows = connected.windowsOnAllDisplays.get(displayId).orEmpty()
            if (windows.isNotEmpty() || SystemClock.uptimeMillis() >= deadline) return windows
            SystemClock.sleep(FIRST_WINDOWS_POLL_MS)
        }
    }

    private fun connect(): UiAutomation {
        val hidden = UiAutomationHidden(handler.looper, UiAutomationConnection())
        hidden.connect(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val connected = Refine.unsafeCast<UiAutomation>(hidden)
        try {
            connected.serviceInfo = connected.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        } catch (t: Throwable) {
            runCatching { hidden.disconnect() }
            throw t
        }
        return connected
    }

    private fun window(window: AccessibilityWindowInfo): JSONObject {
        val root = window.root
        return JSONObject().apply {
            put("type", windowType(window.type))
            window.title?.let { put("title", it.toString()) }
            root?.packageName?.let { put("package", it.toString()) }
            root?.let { put("root", node(it, depth = 0, budget = intArrayOf(MAX_NODES))) }
        }
    }

    /** [budget] 是整個視窗剩下可走的節點數，超過就不再往下走。 */
    private fun node(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): JSONObject {
        budget[0]--
        val bounds = Rect().also(node::getBoundsInScreen)
        val json = JSONObject().apply {
            node.className?.let { put("class", it.toString()) }
            node.text?.let { put("text", it.toString()) }
            node.viewIdResourceName?.let { put("id", it) }
            node.contentDescription?.let { put("desc", it.toString()) }
            put("x", bounds.left)
            put("y", bounds.top)
            put("w", bounds.width())
            put("h", bounds.height())
            put("cx", bounds.centerX())
            put("cy", bounds.centerY())
            put("clickable", node.isClickable)
            put("long_clickable", node.isLongClickable)
            put("checkable", node.isCheckable)
            put("checked", node.isChecked)
            put("enabled", node.isEnabled)
            put("focusable", node.isFocusable)
            put("focused", node.isFocused)
            put("selected", node.isSelected)
            put("scrollable", node.isScrollable)
            put("editable", node.isEditable)
            put("visible", node.isVisibleToUser)
        }
        val children = JSONArray()
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) {
                    Timber.w("view tree truncated at $MAX_NODES nodes")
                    break
                }
                node.getChild(i)?.let { children.put(node(it, depth + 1, budget)) }
            }
        }
        json.put("children", children)
        return json
    }

    private fun windowType(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        else -> "unknown"
    }

    private companion object {
        const val IDLE_RELEASE_MS = 30_000L
        const val FIRST_WINDOWS_TIMEOUT_MS = 1_000L
        const val FIRST_WINDOWS_POLL_MS = 50L
        const val MAX_NODES = 5_000
        const val MAX_DEPTH = 64
    }
}
