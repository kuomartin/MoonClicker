package com.xaxaxax.moonclicker.ocr

import timber.log.Timber

/**
 * OCR 的 JNI 宣告，實作在 `moonclicker_native` 的 `Ocr`。handle 是 native 的 `Ocr*`，用完必須
 * [nativeDestroy]。失敗一律拋 [IllegalStateException]，訊息是 native 端的原因。
 */
internal object OcrNative {

    init {
        try {
            System.loadLibrary("moonclicker_native")
        } catch (ex: UnsatisfiedLinkError) {
            Timber.e(ex, "Failed to load moonclicker_native")
        }
    }

    /** 載入 [packDir] 的 ORT 與模型。ORT 在行程內只載入一次，見 `OrtLoader.h`。 */
    external fun nativeCreate(packDir: String, threads: Int): Long

    external fun nativeDestroy(handle: Long)

    /** 固定形狀的假輸入跑一次偵測＋辨識的毫秒數，給 [OcrCalibrator] 用。 */
    external fun nativeTimeDummyInference(handle: Long): Double

    /**
     * 對影像檔跑 OCR，回傳 UTF-8 JSON：`{"det_ms", "rec_ms", "lines": [{"text", "confidence", "x", "y", "w", "h"}]}`。
     * [detect] 為 false 時把 [roi]（`x, y, w, h`；`null` 為整張）當成單一文字行直接辨識。
     * 給 androidTest 量準確率與延遲用；腳本走的是影格，不經過這裡。
     */
    external fun nativeReadImage(handle: Long, imagePath: String, roi: IntArray?, detect: Boolean): ByteArray

    /**
     * `vision.*` 的文字比對（`TextMatch.h`），回傳 `[相似度, 起點, 終點]`，起訖是 [line] 的 codepoint 索引。
     * 給 androidTest 驗比對規則用；不需要 OCR 套件。
     */
    external fun nativeMatchText(target: String, line: String, exact: Boolean): DoubleArray
}
