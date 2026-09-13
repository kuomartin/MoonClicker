package com.xaxaxax.relc.script.puppet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * `vision.*` 要找的那張圖，由一個純函式產生。
 *
 * [PuppetActivity] 把它畫到畫面上，測試把**同一個 bitmap** 寫成模板 PNG——所以模板與畫面
 * 上的內容是同一份資料，比對命不命中只取決於管線（GLES 分發、ImageReader、OpenCV），
 * 不取決於「這兩張圖像不像」。比對失敗時就是管線壞了，沒有第三種解釋。
 *
 * 刻意用黑白棋盤格：高對比讓 TM_CCOEFF_NORMED 的分數乾脆，而且沒有顏色，所以就算影格在
 * 某一段被當成 BGR 或 RGB 讀，結果也一樣——排除掉一整類會讓人追很久的通道順序問題。
 */
internal object PuppetMarker {

    const val SIZE = 160
    private const val CELL = 20

    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        canvas.drawColor(Color.WHITE)
        paint.color = Color.BLACK
        val cells = SIZE / CELL
        for (row in 0 until cells) {
            for (col in 0 until cells) {
                if ((row + col) % 2 == 0) continue
                canvas.drawRect(
                    (col * CELL).toFloat(),
                    (row * CELL).toFloat(),
                    ((col + 1) * CELL).toFloat(),
                    ((row + 1) * CELL).toFloat(),
                    paint,
                )
            }
        }
        // 中央一塊實心，讓「有沒有對準」在肉眼與在分數上都看得出來。
        paint.color = Color.BLACK
        val inset = (SIZE / 2 - CELL).toFloat()
        canvas.drawRect(inset, inset, SIZE - inset, SIZE - inset, paint)
        return bitmap
    }

    fun png(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
