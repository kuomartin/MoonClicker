package com.xaxaxax.moonclicker.engine

import android.content.Context
import android.hardware.display.DisplayManager
import com.xaxaxax.moonclicker.IMoonClickerService
import timber.log.Timber

/** 啟動 native runtime（腳本或 REPL）時要交給它的顯示器資訊。 */
internal data class DisplayLaunchGeometry(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    /** 純粹給 Lua `screen.rotation` 讀的中繼資料，不影響任何座標換算，見 nativeStart 的文件。 */
    val rotation: Int,
    val isPhysical: Boolean,
) {
    companion object {
        /**
         * AImageReader 開成 distributor 目前輸出的影格尺寸（ADR-0017：轉正後的自然尺寸，
         * 已經隨 v 互換長寬）——問服務要這個值，不自己從（邏輯尺寸, rotation）回推。
         * 緩衝區一開就是整場執行，中途 VD 再旋轉不會跟著變，見 ADR-0017 的 Consequences。
         *
         * @return 讀不到影格尺寸時為 null。[0, 0] 也代表「這個 id 不是服務建的顯示器」，與其用推出來的
         *   幾何硬跑，不如當場停下來講清楚。
         */
        fun read(context: Context, service: IMoonClickerService, displayId: Int): DisplayLaunchGeometry? {
            val surface = try {
                service.getDisplaySurfaceSize(displayId)
            } catch (t: Throwable) {
                Timber.e(t, "getDisplaySurfaceSize($displayId) failed")
                null
            }
            if (surface == null || surface.size < 2 || surface[0] <= 0 || surface[1] <= 0) return null

            val rotation = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)?.rotation ?: 0
            val displayInfo = runCatching { service.getDisplayInfo(displayId) }.getOrNull()
            val isPhysical = displayInfo?.isPhysical ?: (displayId == 0)
            return DisplayLaunchGeometry(surface[0], surface[1], rotation, isPhysical)
        }
    }
}
