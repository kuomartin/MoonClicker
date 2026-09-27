package com.xaxaxax.moonclicker.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** 一種執行緒數的校準結果：一次偵測＋辨識的中位數毫秒數。 */
data class OcrCalibration(val threads: Int, val millis: Double)

/**
 * 決定 OCR 的執行緒數（ADR-0018：最佳值因機型而異，設錯慢 2～3 倍）。
 *
 * 每種執行緒數各自載入一次模型，暖機 1 次後量 [REPEATS] 次取中位數。輸入是固定形狀的假張量，
 * 推論耗時只取決於形狀，所以不需要校準圖片。腳本執行中不要校準：同時推論會互相拖慢。
 */
object OcrCalibrator {

    val CANDIDATES = listOf(1, 2, 4)
    private const val REPEATS = 3

    /** 依 [candidates] 的順序回傳每一種的結果；超過 CPU 核心數的略過。 */
    suspend fun measure(packDir: File, candidates: List<Int> = CANDIDATES): List<OcrCalibration> =
        withContext(Dispatchers.Default) {
            val cores = Runtime.getRuntime().availableProcessors()
            candidates.filter { it <= cores }.map { threads ->
                currentCoroutineContext().ensureActive()
                val handle = OcrNative.nativeCreate(packDir.absolutePath, threads)
                try {
                    OcrNative.nativeTimeDummyInference(handle)
                    val times = List(REPEATS) {
                        currentCoroutineContext().ensureActive()
                        OcrNative.nativeTimeDummyInference(handle)
                    }
                    OcrCalibration(threads, times.sorted()[REPEATS / 2])
                } finally {
                    OcrNative.nativeDestroy(handle)
                }
            }
        }

    fun fastest(results: List<OcrCalibration>): Int? = results.minByOrNull { it.millis }?.threads
}
