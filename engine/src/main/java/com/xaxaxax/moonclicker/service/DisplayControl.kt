package com.xaxaxax.moonclicker.service

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayHidden
import android.view.IWindowManager
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo
import dev.rikka.tools.refine.Refine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 任意顯示器（實體螢幕、別的進程建立的虛擬顯示器、本服務建立的 VD）的查詢與 WindowManager 設定：
 * 尺寸（邏輯、surface 空間）、彙整給呼叫端的 [MoonClickerDisplayInfo]、旋轉、輸入法。只有本服務建立的
 * VD 才有的事（建立、resize、銷毀、睡眠）在 [VirtualDisplayLifecycle]。
 */
internal class DisplayControl(
    private val displayManager: DisplayManager,
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
    private val displayMirroring: DisplayMirroring,
    private val windowManager: () -> IWindowManager,
) {
    fun getDisplaySize(displayId: Int): IntArray {
        val display = displayManager.getDisplay(displayId) ?: return intArrayOf(0, 0)
        val (width, height) = display.logicalSize()
        return intArrayOf(width, height)
    }

    /**
     * consumer 收到的影格尺寸（ADR-0017）。所有 consumer 都在這裡拿，不自己推。
     *
     * - 有 distributor 的 VD（自己建的，或 [DisplayMirroring] 的 VD 版鏡像）：distributor 依 VD
     *   **目前**的 rotation 把影格轉正，所以是建立尺寸在 90/270 時互換長寬。兩個值同進程讀取。
     * - 其他存在的顯示器（display 0 未開鏡像、legacy 鏡像）：邏輯尺寸，也就是
     *   [DisplayMirroring] 建鏡像 distributor 時用的尺寸。
     * - 不存在：[0, 0]，讓呼叫端當場失敗。
     *
     * 會隨旋轉改變；consumer 只在啟動、連線當下讀一次，見 ADR-0017 的 Consequences。
     * 不隨旋轉改變的建立尺寸在 [MoonClickerDisplayInfo.surfaceWidth]。
     */
    fun getDisplaySurfaceSize(displayId: Int): IntArray {
        val actualId = displayMirroring.resolveDistributorId(displayId)
        virtualDisplayLifecycle.surfaceSize(actualId)?.let { (surfaceWidth, surfaceHeight) ->
            val rotation = displayManager.getDisplay(actualId)?.rotation ?: 0
            return if (rotation and 1 != 0) {
                intArrayOf(surfaceHeight, surfaceWidth)
            } else {
                intArrayOf(surfaceWidth, surfaceHeight)
            }
        }

        val display = displayManager.getDisplay(displayId) ?: run {
            Timber.w("getDisplaySurfaceSize($displayId): display not found")
            return intArrayOf(0, 0)
        }
        val (width, height) = display.logicalSize()
        return intArrayOf(width, height)
    }

    fun getDisplayInfo(displayId: Int): MoonClickerDisplayInfo? {
        val display = displayManager.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        // 系統回報的類型優先：vdStore 只認得自己建立的 VD，遇到別的進程建立的虛擬顯示
        // （例如 #73 的情況）會誤判成實體，見 docs/research/display-gettype-api-levels.md。
        val isPhysical = !(display.isVirtualType() ?: virtualDisplayLifecycle.isKnownVirtualDisplay(displayId))
        val isMirrorActive = displayMirroring.isDisplayMirrorActive(displayId)
        return MoonClickerDisplayInfo().apply {
            this.displayId = displayId
            this.name = virtualDisplayLifecycle.requestedName(displayId) ?: display.name ?: "Display $displayId"
            this.width = metrics.widthPixels
            this.height = metrics.heightPixels
            this.densityDpi = metrics.densityDpi
            this.isPhysical = isPhysical
            this.isMirrorActive = isMirrorActive
            this.isManaged = virtualDisplayLifecycle.isKnownVirtualDisplay(displayId)
            this.canSleep = virtualDisplayLifecycle.canSleep(displayId)
            virtualDisplayLifecycle.surfaceSize(displayId)?.let { (w, h) ->
                this.surfaceWidth = w
                this.surfaceHeight = h
            }
        }
    }

    fun getDisplayInfos(): Array<MoonClickerDisplayInfo> {
        val allDisplays = displayManager.displays ?: emptyArray()
        val internalMirrorVdIds = displayMirroring.internalMirrorDisplayIds()
        return allDisplays
            .filter { it.displayId !in internalMirrorVdIds }
            .mapNotNull { getDisplayInfo(it.displayId) }
            .toTypedArray()
    }

    fun setDisplayRotation(displayId: Int, rotation: Int): Boolean {
        val quarterTurns = rotation and 3
        // API 29 起才有 freezeDisplayRotation；27–28 沒有可用的 Java 路徑，退回 command line。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val wm = windowManager()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    wm.freezeDisplayRotation(displayId, quarterTurns, "MoonClicker")
                } else {
                    @Suppress("DEPRECATION")
                    wm.freezeDisplayRotation(displayId, quarterTurns)
                }
                return true
            } catch (t: Throwable) {
                // 真正的失敗，不退回 shell——那會把可回報的錯誤變成靜默成功。
                Timber.e(t, "freezeDisplayRotation(%d, %d) failed", displayId, quarterTurns)
                return false
            }
        }
        return setDisplayRotationViaShell(displayId, quarterTurns)
    }

    /** 被本服務關掉鍵盤的顯示器原本的 IME policy，開回來時還原成它。 */
    private val imePolicyBeforeDisabled = ConcurrentHashMap<Int, Int>()

    /**
     * 關掉時把 IME policy 設成 HIDE：該顯示器上的視窗不綁定輸入法，按鍵直接送到有焦點的 view，
     * 不會被使用者的輸入法組字（注音會把 `rick` 變成中文）。只影響之後才開始的輸入，所以要在
     * 顯示器上啟動 app 之前呼叫。`setDisplayImePolicy` 從 API 31 才有，更舊的版本回傳 false。
     */
    fun setDisplayKeyboardEnabled(displayId: Int, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val wm = windowManager()
            if (enabled) {
                imePolicyBeforeDisabled.remove(displayId)?.let { wm.setDisplayImePolicy(displayId, it) }
            } else {
                val current = wm.getDisplayImePolicy(displayId)
                if (current != DISPLAY_IME_POLICY_HIDE) {
                    imePolicyBeforeDisabled.putIfAbsent(displayId, current)
                    wm.setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_HIDE)
                }
            }
            true
        } catch (t: Throwable) {
            Timber.e(t, "setDisplayKeyboardEnabled(%d, %b) failed", displayId, enabled)
            false
        }
    }

    /**
     * API 27–28 專用的退路——該版本區間沒有 `freezeDisplayRotation`。
     * 服務以 shell UID 執行，可直接呼叫 `cmd`。
     */
    private fun setDisplayRotationViaShell(displayId: Int, quarterTurns: Int): Boolean = try {
        val process = ProcessBuilder(
            "cmd", "window", "user-rotation", "-d", displayId.toString(), "lock", quarterTurns.toString()
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.waitFor()
        Timber.d("cmd window user-rotation -d %d lock %d -> exit=%d %s", displayId, quarterTurns, exit, output)
        exit == 0
    } catch (t: Throwable) {
        Timber.e(t, "cmd window user-rotation failed for display %d", displayId)
        false
    }

    companion object {
        /**
         * `Display.TYPE_*`：`@hide` 常數，Refine 沒辦法 stub static final int（會被編譯器內聯掉），
         * 只能照 AOSP 原始碼把值抄過來（見 docs/research/display-gettype-api-levels.md）。
         */
        private const val DISPLAY_TYPE_VIRTUAL = 5

        /**
         * `Display.getType()` 全程都是 `@hide`（見 docs/research/display-gettype-api-levels.md），
         * 單一反射路徑涵蓋 API 27~37；反射失敗回傳 null，讓呼叫端自行 fallback。
         */
        private fun Display.isVirtualType(): Boolean? =
            runCatching { Refine.unsafeCast<DisplayHidden>(this).type == DISPLAY_TYPE_VIRTUAL }
                .getOrNull()
    }
}

/** WindowManager 套用旋轉後的尺寸（`getRealSize`）。 */
internal fun Display.logicalSize(): Pair<Int, Int> {
    val outSize = android.graphics.Point()
    @Suppress("DEPRECATION")
    getRealSize(outSize)
    return outSize.x to outSize.y
}

/** `WindowManager.DISPLAY_IME_POLICY_HIDE`（@hide）。 */
private const val DISPLAY_IME_POLICY_HIDE = 2
