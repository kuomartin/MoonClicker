package com.xaxaxax.moonclicker.script.puppet

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

    /**
     * 同心方框，所以它**轉 90 度還是自己**。
     *
     * 這件事是旋轉測試的前提。影格在 surface 空間，顯示器轉 90 度時內容是被轉「進」那個
     * 緩衝區的，而 `TM_CCOEFF_NORMED` 不是旋轉不變的——用一個不對稱的圖樣（例如棋盤格，
     * 它轉 90 度會反相）當模板，旋轉後根本比不中，測試就會為了錯的理由失敗。
     *
     * 另一條路是「測試自己把模板也轉 90 度」，但那會把 surface 空間的旋轉方向寫死進測試，
     * 而方向正是這個測試該抓的東西——猜錯時只會被翻到綠為止。用旋轉對稱的圖樣就沒有這個
     * 誘惑：**位置**成為唯一被斷言的東西，而位置是 puppet 獨立回報的。
     */
    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        var inset = 0
        var black = true
        while (inset < SIZE / 2) {
            paint.color = if (black) Color.BLACK else Color.WHITE
            canvas.drawRect(
                inset.toFloat(),
                inset.toFloat(),
                (SIZE - inset).toFloat(),
                (SIZE - inset).toFloat(),
                paint,
            )
            black = !black
            inset += CELL
        }
        return bitmap
    }

    fun png(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
