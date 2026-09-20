package com.xaxaxax.moonclicker.lua

import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.script.ScriptHost
import timber.log.Timber

/**
 * 純粹的 JNI 宣告。這裡刻意不放任何邏輯——Lua API 的 Kotlin 端實作全部在
 * [ScriptHost]，原生引擎只認得它一個 upcall 對象。
 *
 * `internal` 是刻意的：其他模組走 [com.xaxaxax.moonclicker.engine.ScriptEngine]。
 */
internal object LuaNative {

    init {
        try {
            System.loadLibrary("moonclicker_native")
        } catch (ex: UnsatisfiedLinkError) {
            Timber.e(ex, "Failed to load moonclicker_native")
        }
    }

    /**
     * @param withVision 是否掛 ImageReader 取影格。實體螢幕拿不到影格，必須傳 false。
     * @param surfaceWidth 影格尺寸——distributor 轉正後的自然尺寸，整場執行固定不變
     *   （AImageReader 不會在 VD 中途旋轉時重開，見 ADR-0017）。
     * @param initialRotation 啟動當下的 rotation，純粹是給 Lua `screen.rotation` 讀的中繼資料，
     *   不影響任何座標換算——影格已經是邏輯空間。
     * @param scriptDir 腳本資料夾，內含 main.lua。
     */
    external fun nativeStart(
        host: ScriptHost,
        service: IMoonClickerService,
        displayId: Int,
        isPhysical: Boolean,
        withVision: Boolean,
        surfaceWidth: Int,
        surfaceHeight: Int,
        initialRotation: Int,
        scriptDir: String,
    ): Boolean

    external fun nativeStop()

    external fun nativeIsRunning(): Boolean
}
