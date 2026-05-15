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

        const val TM_CCOEFF_NORMED = 5
    }

    /**
     * 原生模板匹配
     * @param screen 螢幕截圖 (RGBA_8888)
     * @param target 目標小圖 (RGBA_8888)
     * @param x ROI 起點 X
     * @param y ROI 起點 Y
     * @param width ROI 寬度
     * @param height ROI 高度
     * @param method 匹配演算法
     */
    external fun matchTemplateNative(
        screen: Bitmap,
        target: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        method: Int = TM_CCOEFF_NORMED
    ): DetectionResult?
}
