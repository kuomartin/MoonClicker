package com.xaxaxax.relc.lua

import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.script.ScriptHost
import timber.log.Timber

/**
 * 純粹的 JNI 宣告。這裡刻意不放任何邏輯——Lua API 的 Kotlin 端實作全部在
 * [ScriptHost]，原生引擎只認得它一個 upcall 對象。
 *
 * `internal` 是刻意的：其他模組走 [com.xaxaxax.relc.engine.ScriptEngine]。
 */
internal object LuaNative {

    init {
        try {
            System.loadLibrary("relc_native")
        } catch (ex: UnsatisfiedLinkError) {
            Timber.e(ex, "Failed to load relc_native")
        }
    }

    /**
     * @param withVision 是否掛 ImageReader 取影格。實體螢幕拿不到影格，必須傳 false。
     * @param surfaceWidth 顯示器**建立時**的尺寸（surface 空間，不是 getDisplaySize 回的邏輯尺寸）。
     * @param initialRotation 啟動當下的 rotation，讓腳本第一行讀 screen.width 就是對的。
     * @param scriptDir 腳本資料夾，內含 main.lua。
     */
    external fun nativeStart(
        host: ScriptHost,
        service: IRelcV2Service,
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

    /** 記錄虛擬顯示當前的 rotation，供座標轉換使用。**不會旋轉任何東西。** */
    external fun nativeSetDisplayRotation(rotation: Int)
}
