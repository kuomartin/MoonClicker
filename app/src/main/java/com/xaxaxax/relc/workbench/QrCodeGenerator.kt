package com.xaxaxax.relc.workbench

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Workbench 配對用的 QR code（見 #56）。[encode] 回傳純粹的 [BitMatrix] 方便在 JVM 單元測試裡
 * 驗證編碼內容，[toBitmap] 才是畫到畫面上要用的 Android 型別。
 */
object QrCodeGenerator {
    fun encode(content: String, size: Int = 512): BitMatrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )

    fun toBitmap(matrix: BitMatrix): Bitmap {
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) BLACK else WHITE)
            }
        }
        return bitmap
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
