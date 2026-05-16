package com.xaxaxax.relc.display.cv

import android.graphics.Bitmap
import timber.log.Timber

/**
 * 原生 OpenCV 辨識包裝類
 */
class NativeDetector {

    companion object {
        init {
            try {
                System.loadLibrary("relc_native")
            } catch (ex: UnsatisfiedLinkError) {
                Timber.e(ex, "Failed to load relc_native")
            }
        }
    }

    /**
     * 啟動原生引擎 (Hot Loop)
     * @param service Shizuku 服務，用於注入事件
     * @param width 螢幕寬度
     * @param height 螢幕高度
     * @param script Lua 腳本內容
     * @return 供 VirtualDisplay 使用的 Surface
     */
    external fun startEngine(
        service: com.xaxaxax.relc.IRelcV2Service,
        width: Int,
        height: Int,
        script: String
    ): android.view.Surface?


    /**
     * 停止原生引擎
     */
    external fun stopEngine()

    /**
     * 檢查原生引擎是否正在運行
     */
    external fun isEngineRunning(): Boolean

    /**
     * 進行模板匹配
     */
    external fun matchTemplateNative(
        screenBitmap: Bitmap,
        targetBitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        method: Int = 5 // cv::TM_CCOEFF_NORMED
    ): DetectionResult?
}
