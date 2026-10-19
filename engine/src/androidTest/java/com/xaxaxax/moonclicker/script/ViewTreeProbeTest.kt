package com.xaxaxax.moonclicker.script

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #196 的實機量測：shell 身分的 UiAutomation 看不看得到虛擬顯示上的視窗與節點、取一次要多久。
 *
 * instrumentation 的 UiAutomation 與 production 預計的做法（Shizuku 的 shell 進程自己建
 * `UiAutomation(looper, UiAutomationConnection())`）走同一條 `registerUiTestAutomationService`，
 * 權限一樣是 shell 的 `RETRIEVE_WINDOW_CONTENT`。數字寫進 logcat 的 `ViewTreeProbe` tag。
 */
@RunWith(AndroidJUnit4::class)
class ViewTreeProbeTest {

    @get:Rule
    val env = Tier1Env()

    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Before
    fun retrieveInteractiveWindows() {
        // getWindowsOnAllDisplays 是 API 30。
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @Test
    fun puppet_on_a_virtual_display() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)

        probe(displayId, label = "puppet")
    }

    @Test
    fun settings_on_a_virtual_display() {
        // aosp-atd 映像沒裝 Settings。
        assumeTrue(
            "$SETTINGS is not installed",
            env.context.packageManager.getLaunchIntentForPackage(SETTINGS) != null,
        )
        val displayId = env.createDisplay()
        assertTrue(env.service.launchInDisplay(SETTINGS, displayId, false))

        probe(displayId, label = "settings", expectPackage = SETTINGS)
    }

    private fun probe(displayId: Int, label: String, expectPackage: String? = null) {
        val windows = awaitWindows(displayId, expectPackage)
        report("$label api=${Build.VERSION.SDK_INT} display=$displayId windows=${windows.map(::describe)}")
        assertTrue(
            "no windows for display $displayId; displays with windows: ${ui.windowsOnAllDisplays.keys()}\n" +
                    env.windowsOnDisplay(displayId),
            windows.isNotEmpty(),
        )

        // 顯示上可能還有別的 app 視窗（例如系統的跨裝置服務），要挑目標 package 的那個。
        val app = windows.first { it.isApp(expectPackage) }
        val samples = (1..RUNS).map {
            val start = SystemClock.elapsedRealtimeNanos()
            val root = checkNotNull(app.root) { "window ${describe(app)} has no root" }
            val nodes = countNodes(root)
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6 to nodes
        }
        val ms = samples.map { it.first }.sorted()
        report(
            "$label nodes=${samples.last().second} full-tree ms: first=${"%.1f".format(samples.first().first)} " +
                    "median=${"%.1f".format(ms[ms.size / 2])} max=${"%.1f".format(ms.last())}"
        )
        report("$label tree:\n${dump(checkNotNull(app.root), depth = 0, limit = 40)}")
    }

    private fun android.util.SparseArray<*>.keys(): List<Int> = (0 until size()).map(::keyAt)

    private fun awaitWindows(displayId: Int, expectPackage: String?): List<AccessibilityWindowInfo> {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var windows: List<AccessibilityWindowInfo> = emptyList()
        while (SystemClock.uptimeMillis() < deadline) {
            windows = ui.windowsOnAllDisplays.get(displayId).orEmpty()
            if (windows.any { it.isApp(expectPackage) }) return windows
            SystemClock.sleep(200)
        }
        return windows
    }

    private fun AccessibilityWindowInfo.isApp(expectPackage: String?): Boolean =
        type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (expectPackage == null || root?.packageName == expectPackage)

    private fun countNodes(node: AccessibilityNodeInfo): Int =
        1 + (0 until node.childCount).sumOf { i -> node.getChild(i)?.let(::countNodes) ?: 0 }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, limit: Int): String = buildString {
        var remaining = limit
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (remaining-- <= 0) return
            val bounds = android.graphics.Rect().also(n::getBoundsInScreen)
            appendLine("${"  ".repeat(d)}${n.className} text=${n.text} id=${n.viewIdResourceName} bounds=$bounds")
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(node, depth)
    }

    private fun describe(window: AccessibilityWindowInfo): String =
        "{id=${window.id} type=${window.type} title=${window.title} pkg=${window.root?.packageName}}"

    private fun report(message: String) {
        message.lines().forEach { Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "ViewTreeProbe"
        const val SETTINGS = "com.android.settings"
        const val RUNS = 10
    }
}
