package com.xaxaxax.moonclicker.lua

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
     * @param ocrPackDir OCR 套件目錄；空字串表示未安裝或不支援。OCR 在腳本第一次用到時才載入。
     * @param ocrThreads OCR 的執行緒數。
     * @return native ScriptRuntime 的 handle，交給 [nativeStop]；0 表示沒有啟動。
     */
    external fun nativeStart(
        host: ScriptHost,
        displayId: Int,
        isPhysical: Boolean,
        withVision: Boolean,
        surfaceWidth: Int,
        surfaceHeight: Int,
        initialRotation: Int,
        scriptDir: String,
        ocrPackDir: String,
        ocrThreads: Int,
    ): Long

    /**
     * 以 REPL 模式啟動：執行緒不跑 `main.lua`，等 [nativeReplEval] 送來的程式碼。參數同 [nativeStart]，
     * [scriptDir] 是 `require` 與模板路徑的根。結果以 [ScriptHost.replResult] 回報。
     */
    external fun nativeStartRepl(
        host: ScriptHost,
        displayId: Int,
        isPhysical: Boolean,
        withVision: Boolean,
        surfaceWidth: Int,
        surfaceHeight: Int,
        initialRotation: Int,
        scriptDir: String,
        ocrPackDir: String,
        ocrThreads: Int,
    ): Long

    /** 排入一段 UTF-8 程式碼，依序執行。 */
    external fun nativeReplEval(handle: Long, id: Long, code: ByteArray)

    /** 中斷正在執行的那一段；沒有在跑時不做事。 */
    external fun nativeReplInterrupt(handle: Long)

    /** 停止並等 Lua 執行緒結束，然後釋放 [handle]。每個 handle 只能呼叫一次。 */
    external fun nativeStop(handle: Long)
}
