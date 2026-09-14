package com.xaxaxax.relc.workbench

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

private class BitMatrixLuminanceSource(private val matrix: BitMatrix) :
    com.google.zxing.LuminanceSource(matrix.width, matrix.height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = row?.takeIf { it.size >= width } ?: ByteArray(width)
        for (x in 0 until width) {
            out[x] = if (matrix[x, y]) 0 else 0xFF.toByte()
        }
        return out
    }

    override fun getMatrix(): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
            }
        }
        return out
    }
}

class QrCodeGeneratorTest {
    @Test
    fun `encoded matrix decodes back to the original content`() {
        val content = "192.168.1.23:8787"

        val matrix = QrCodeGenerator.encode(content)

        val bitmap = BinaryBitmap(HybridBinarizer(BitMatrixLuminanceSource(matrix)))
        val result = QRCodeReader().decode(bitmap)

        assertEquals(content, result.text)
    }
}
