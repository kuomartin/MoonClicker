package com.xaxaxax.relc.display.cv

import androidx.annotation.Keep

/**
 * 影像辨識結果
 * @param isDetected 是否有找到目標
 * @param centerX 找到目標的中心點 X (全螢幕座標)
 * @param centerY 找到目標的中心點 Y (全螢幕座標)
 * @param confidenceRate 相似度門檻 (0.0 - 1.0)
 * @param colorDiff 顏色差異 (0.0 - 100.0, 越小表示顏色越接近)
 */
@Keep
data class DetectionResult(
    var isDetected: Boolean = false,
    var centerX: Int = 0,
    var centerY: Int = 0,
    var confidenceRate: Double = 0.0,
    var colorDiff: Double = 0.0
)
