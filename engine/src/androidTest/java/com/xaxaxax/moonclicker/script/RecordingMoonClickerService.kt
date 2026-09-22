package com.xaxaxax.moonclicker.script

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.xaxaxax.moonclicker.IMoonClickerService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一個在測試進程裡的 [IMoonClickerService]：把 Lua API 要求它做的事**記下來**，而不是真的做。
 *
 * 這是整套 Lua 測試的支點。`IMoonClickerService` 本來就是 Shizuku 的邊界，所以只要換掉它，
 * 從 `main.lua` 一路到 `ScriptHost` 的每一段——native 的參數解析、座標攤平、pointer
 * 狀態機、錯誤傳遞——都能在**沒有 Shizuku、沒有虛擬顯示、沒有實體裝置權限**的情況下跑，
 * 在任何模擬器上都是幾百毫秒的事。
 *
 * 這一層刻意**不**模擬真實服務的非同步注入：`multiTouchSwipe` 在真實服務裡是 `oneway`
 * 且會依 duration 插值，這裡直接回來。所以能驗的是「引擎送出了什麼」，不是「螢幕上發生
 * 了什麼」。後者要真的顯示器與一個被操作的 app（見 `docs/lua-api-testing.md` 的 Tier 1）。
 *
 * 用不到的方法一律丟例外，而不是回傳 0/false——測試走到沒設想過的路徑時要當場炸掉，
 * 別讓它靜靜地通過。
 */
internal class RecordingMoonClickerService(
    /** 顯示器**建立時**的尺寸；`ScriptEngine.start` 會拿它去開 ImageReader。 */
    private val surfaceSize: IntArray = intArrayOf(1080, 1920),
    /** 邏輯尺寸；旋轉時與 surface 尺寸長寬互換。 */
    private val logicalSize: IntArray = surfaceSize,
) : IMoonClickerService.Stub() {

    sealed interface Call

    /** `input.tap` / `swipe` / `multi_swipe` / `down` / `move` / `up` 最終都變成這個。 */
    data class Swipe(
        val pointerId: Int,
        val displayId: Int,
        val points: List<Int>,
        val durationMs: Long,
        val keep: Boolean,
    ) : Call

    data class Key(val keyCode: Int, val action: Int, val displayId: Int) : Call

    data class Launch(val packageName: String, val displayId: Int) : Call

    val calls = CopyOnWriteArrayList<Call>()

    val swipes: List<Swipe> get() = calls.filterIsInstance<Swipe>()
    val launches: List<Launch> get() = calls.filterIsInstance<Launch>()

    /** 只看 ACTION_DOWN——`ScriptHost.key` 每個按鍵都送 down + up 一對。 */
    val keyDowns: List<Key>
        get() = calls.filterIsInstance<Key>().filter { it.action == KeyEvent.ACTION_DOWN }

    override fun multiTouchSwipe(
        pointerId: Int,
        displayId: Int,
        points: IntArray,
        duration: Long,
        keep: Boolean,
    ) {
        calls += Swipe(pointerId, displayId, points.toList(), duration, keep)
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        calls += Key(event.keyCode, event.action, displayId)
        return true
    }

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        calls += Launch(packageName, displayId)
        return true
    }

    override fun getDisplaySurfaceSize(displayId: Int): IntArray = surfaceSize

    override fun getDisplaySize(displayId: Int): IntArray = logicalSize

    override fun addVirtualDisplaySurface(displayId: Int, surface: Surface): Int =
        if (mirrorActive) 1001 else -1

    override fun removeVirtualDisplaySurface(displayId: Int, handle: Int): Boolean = true

    // --- 這一層用不到的 ------------------------------------------------------

    override fun setOverlayAllowed(packageName: String): Boolean = unused("setOverlayAllowed")

    override fun grantRuntimePermission(packageName: String, permissionName: String): Boolean =
        unused("grantRuntimePermission")

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
    ): Int = unused("createVirtualDisplay")

    override fun destroyVirtualDisplay(displayId: Int): Boolean = unused("destroyVirtualDisplay")

    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean =
        unused("resizeVirtualDisplay")

    override fun getVirtualDisplays(): IntArray = unused("getVirtualDisplays")

    override fun setDisplayRotation(displayId: Int, rotation: Int): Boolean =
        unused("setDisplayRotation")

    override fun getLauncherApps(): MutableList<String> = unused("getLauncherApps")

    override fun refreshLauncherApps(): MutableList<String> = unused("refreshLauncherApps")

    override fun getPointers(displayId: Int): IntArray = unused("getPointers")

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean =
        unused("injectMotionEvent")

    override fun debug(input: String): String = unused("debug")

    var mirrorActive: Boolean = true

    override fun acquireDisplayMirror(displayId: Int): Boolean {
        mirrorActive = true
        return true
    }

    override fun releaseDisplayMirror(displayId: Int): Boolean {
        mirrorActive = false
        return true
    }

    override fun isDisplayMirrorActive(displayId: Int): Boolean = mirrorActive

    override fun getDisplayInfo(displayId: Int): com.xaxaxax.moonclicker.MoonClickerDisplayInfo =
        com.xaxaxax.moonclicker.MoonClickerDisplayInfo().apply {
            this.displayId = displayId
            this.name = "Recording Display $displayId"
            this.width = logicalSize[0]
            this.height = logicalSize[1]
            this.densityDpi = 420
            this.isPhysical = (displayId == 0)
            this.isMirrorActive = mirrorActive
        }

    override fun getDisplayInfos(): Array<com.xaxaxax.moonclicker.MoonClickerDisplayInfo> =
        arrayOf(getDisplayInfo(0))

    override fun destroy() {
        unused("destroy")
    }

    private fun unused(name: String): Nothing =
        throw UnsupportedOperationException("RecordingMoonClickerService.$name is not part of this tier")
}
