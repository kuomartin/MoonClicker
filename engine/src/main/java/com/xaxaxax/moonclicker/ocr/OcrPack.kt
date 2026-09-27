package com.xaxaxax.moonclicker.ocr

import android.os.Build
import android.os.Process

/**
 * 一個 ABI 的 OCR 套件：下載位置，以及解壓後每個檔案的 SHA-256。
 *
 * 雜湊釘的是解壓後的檔案而不是 zip：zip 的位元組會隨打包端的 zlib 版本改變，檔案內容不會。
 * 雜湊跟著 APK 一起簽章，下載來源被換掉也驗得出來。套件由 `kuomartin/MoonClicker-ocr-pack`
 * 產出，發新版時這裡的版本、URL 與雜湊一起更新（ADR-0018）。
 */
data class OcrPack(
    val version: Int,
    val abi: String,
    val url: String,
    val files: Map<String, String>,
) {
    companion object {
        const val VERSION = 1

        private const val DET_SHA256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"
        private const val REC_SHA256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"
        private const val DICT_SHA256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d"

        /** x86 只用於 API 27–29 的模擬器，不提供套件。 */
        private val RUNTIME_SHA256 = mapOf(
            "arm64-v8a" to "df5d25c72a868dca773597c71e2000756d43fe4d70ade516d3693c54e12e0ada",
            "x86_64" to "f59d4d59d4c71532028d56ab6ca1dae62c3838c7982adfdad7f2560048b89ed9",
        )

        fun forAbi(abi: String): OcrPack? {
            val runtime = RUNTIME_SHA256[abi] ?: return null
            return OcrPack(
                version = VERSION,
                abi = abi,
                url = "https://github.com/kuomartin/MoonClicker-ocr-pack/releases/download/v$VERSION/ocr-pack-$abi.zip",
                files = mapOf(
                    LIBRARY to runtime,
                    DET to DET_SHA256,
                    REC to REC_SHA256,
                    DICT to DICT_SHA256,
                ),
            )
        }

        /**
         * 這個行程的 ABI 對應的套件；沒有就是不支援。`libonnxruntime.so` 必須與行程同一個 ABI，
         * 所以看的是行程位元數，而不是裝置支援的第一個 ABI。
         */
        fun forThisProcess(): OcrPack? =
            if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.firstOrNull()?.let(::forAbi) else null

        /** 行程的 ABI 名稱，給「不支援」的訊息用。 */
        fun processAbi(): String =
            (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
                .firstOrNull() ?: "unknown"

        const val LIBRARY = "libonnxruntime.so"
        const val DET = "det.onnx"
        const val REC = "rec.onnx"
        const val DICT = "dict.txt"
    }
}
