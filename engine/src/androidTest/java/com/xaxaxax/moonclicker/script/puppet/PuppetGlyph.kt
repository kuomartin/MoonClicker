package com.xaxaxax.moonclicker.script.puppet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 一個「Γ」字形，**四個旋轉角看起來都不一樣**。
 *
 * 這是 [PuppetMarker] 的相反面。那一個刻意做成旋轉對稱，好讓旋轉下的**座標**換算能被單獨
 * 驗證；代價是它對**方向**完全盲目——把旋轉方向寫反，它照樣比中。
 *
 * 這一個補上那個洞：影格在 surface 空間，顯示器轉 90 度時內容被轉進緩衝區，而
 * `TM_CCOEFF_NORMED` 不是旋轉不變的。所以「直立時截的模板在橫向還中不中」這件事，只有用
 * 不對稱的圖樣才問得出來。
 *
 * 一樣是純黑白：影格在某一段被當成 BGR 或 RGB 讀都不影響，排除掉通道順序那一類雜訊。
 */
internal object PuppetGlyph {

    const val SIZE = 160
    private const val ARM = 40

    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.WHITE }

        canvas.drawColor(Color.BLACK)
        // 上緣一條、左緣一條，湊成 Γ。轉 90/180/270 都會落到別的角，四個角度互不相同。
        canvas.drawRect(0f, 0f, (SIZE - ARM).toFloat(), ARM.toFloat(), paint)
        canvas.drawRect(0f, 0f, ARM.toFloat(), SIZE.toFloat(), paint)
        return bitmap
    }

    fun png(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
