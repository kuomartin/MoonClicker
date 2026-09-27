package com.xaxaxax.moonclicker.ocr

import java.io.File

/** 腳本執行時載入 OCR 所需的設定：已安裝的套件目錄與執行緒數。 */
data class OcrRuntime(val packDir: File, val threads: Int)
