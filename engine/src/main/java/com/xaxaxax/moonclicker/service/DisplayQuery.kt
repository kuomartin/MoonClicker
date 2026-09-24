package com.xaxaxax.moonclicker.service

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.DisplayHidden
import android.view.WindowManagerGlobal
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo
import com.xaxaxax.moonclicker.script.DisplayGeometry
import dev.rikka.tools.refine.Refine
import timber.log.Timber

/** 顯示器查詢：尺寸（邏輯／surface 空間）、旋轉、以及彙整給呼叫端的 [MoonClickerDisplayInfo]。 */
internal class DisplayQuery(
    private val displayManager: DisplayManager,
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
    private val displayMirroring: DisplayMirroring,
) {
    fun getDisplaySize(displayId: Int): IntArray {
        val display = displayManager.getDisplay(displayId) ?: return intArrayOf(0, 0)
        val outSize = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(outSize)
        return intArrayOf(outSize.x, outSize.y)
    }

    /**
     * consumer 目前該用的影格尺寸（見 CONTEXT.md「Surface 空間 / 邏輯空間」，ADR-0017）。
     *
     * - 自己建立的虛擬顯示：distributor 已經把 v 轉正，這裡回的是轉正後的自然尺寸——
     *   建立尺寸依該 VD**目前**的 rotation 決定要不要互換長寬，rotation 跟建立尺寸同一次
     *   呼叫、同進程讀取，沒有時間差。呼叫端若在腳本執行期間再問一次，拿到的會是新值；
     *   但既有 consumer（AImageReader、TextureView）都只在啟動當下讀一次，不會跟著重開，
     *   這是已知限制，見 ADR-0017 的 Consequences。
     * - 實體螢幕：只能由邏輯尺寸與 rotation 推得，兩者同進程讀取，沒有時間差。
     * - 其他 id：回 [0, 0]，讓呼叫端當場失敗，好過帶著可能錯的尺寸跑完整個腳本。
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
        val outSize = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(outSize)
        val (width, height) = DisplayGeometry.surfaceSize(outSize.x, outSize.y, display.rotation)
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
            this.name = display.name ?: "Display $displayId"
            this.width = metrics.widthPixels
            this.height = metrics.heightPixels
            this.densityDpi = metrics.densityDpi
            this.isPhysical = isPhysical
            this.isMirrorActive = isMirrorActive
            this.isManaged = virtualDisplayLifecycle.isKnownVirtualDisplay(displayId)
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
                val wm = WindowManagerGlobal.getWindowManagerService()
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
