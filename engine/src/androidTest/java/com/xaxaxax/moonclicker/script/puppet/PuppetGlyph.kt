package com.xaxaxax.moonclicker.script.puppet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 一個「Γ」字形，**四個旋轉角看起來都不一樣**。
 *
 * [PuppetMarker] 旋轉對稱，對方向完全盲目。distributor 要在源頭把影格轉正（ADR-0017），轉錯
 * 方向時對稱標記照樣比中，而 `TM_CCOEFF_NORMED` 不是旋轉不變的，直立時截的這個模板就會漏。
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
