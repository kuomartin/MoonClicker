package com.xaxaxax.relc.script.puppet

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 被腳本操作的那個 app。
 *
 * 它只做兩件事：把 [PuppetMarker] 畫在一個已知的位置，以及把收到的每一個觸控與按鍵記進
 * [PuppetRecorder]。有了這兩件事，「`vision.find` 找到的位置」與「`input.tap` 真的打到
 * 的位置」就能互相印證——不必去解析畫面上的文字，也不必相信任何一邊的座標換算。
 *
 * 它宣告在 `engine/src/androidTest/AndroidManifest.xml`，所以會隨測試 APK 一起安裝，
 * 套件名就是 `com.xaxaxax.relc.engine.test`——`app.launch` 要的就是這個。
 */
class PuppetActivity : Activity() {

    companion object {
        @Volatile
        private var instance: PuppetActivity? = null

        /**
         * 讓 puppet 宣告一個方向，藉此把它所在的虛擬顯示轉過去。
         *
         * 這是 CONTEXT.md「方向鏈」`Y → VD → X → MainDisplay` 的第一環：**顯示器裡的 app
         * 決定顯示器的方向**。用 `RelcV2Service.setDisplayRotation` 從外面轉是設 user
         * rotation，而 app 宣告的方向會贏過它（`IRelcV2Service.setDisplayRotation` 的註解
         * 就是這麼寫的）——實測在 SM-A217F 上那樣轉不動，顯示器仍是 720x1280。
         *
         * @param orientation `ActivityInfo.SCREEN_ORIENTATION_*`
         */
        fun requestOrientation(orientation: Int) {
            val activity = instance ?: return
            activity.runOnUiThread { activity.requestedOrientation = orientation }
        }

        /**
         * 測試之間一定要呼叫。虛擬顯示被銷毀時上面的 activity 不會跟著消失，它會被搬回
         * 預設顯示器；下一個測試的 `app.launch` 就會把那個既有的 task 撈回前景，而不是在
         * 新的顯示器上重開一個——畫面上什麼都沒有，`vision.*` 於是永遠找不到東西。
         */
        fun finishAndWait(timeoutMs: Long = 5_000) {
            val activity = instance ?: return
            activity.runOnUiThread { activity.finishAndRemoveTask() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (instance != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
    }

    private lateinit var markerView: MarkerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // 螢幕要亮著、鎖著也要顯示：虛擬顯示上的 activity 沒有人會去解鎖它。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        markerView = MarkerView(this)
        setContentView(markerView)
    }

    override fun onResume() {
        super.onResume()
        PuppetRecorder.resumedOnDisplay = display?.displayId ?: -1
    }

    override fun onPause() {
        super.onPause()
        PuppetRecorder.resumedOnDisplay = -1
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        PuppetRecorder.touches += PuppetRecorder.Touch(
            action = event.actionMasked,
            x = event.x,
            y = event.y,
            displayId = display?.displayId ?: -1,
        )
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) PuppetRecorder.keys += event.keyCode
        return super.dispatchKeyEvent(event)
    }

    /**
     * 全黑底 + 一塊 [PuppetMarker]。
     *
     * 標記刻意不放正中央——放中央的話，「找到了」和「座標換算全錯但剛好對稱」看起來會
     * 一模一樣。放在 1/4、1/3 處，錯了就會錯得很明顯。
     */
    private class MarkerView(context: Context) : View(context) {
        private val marker = PuppetMarker.bitmap()
        private val glyph = PuppetGlyph.bitmap()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.DKGRAY)

            val markerAt = Rect(
                width / 4,
                height / 3,
                width / 4 + PuppetMarker.SIZE,
                height / 3 + PuppetMarker.SIZE,
            )
            // 擺在另一個象限，離對稱標記遠一點：兩個都是黑白幾何圖樣，靠得太近時
            // 「比中了但比到隔壁那個」會變成一種很難讀的失敗。
            val glyphAt = Rect(
                width / 2,
                height * 2 / 3,
                width / 2 + PuppetGlyph.SIZE,
                height * 2 / 3 + PuppetGlyph.SIZE,
            )

            // 指定目的矩形，而不是 drawBitmap(bmp, x, y, paint)。後者會做**密度縮放**：
            // bitmap 帶的是預設顯示器的密度，canvas 的目標密度是虛擬顯示器的，兩者不同時
            // 這 160px 的標記會被畫成別的大小——而模板 PNG 還是 160px，
            // TM_CCOEFF_NORMED 不是尺度不變的，於是永遠比不中。
            canvas.drawBitmap(marker, null, markerAt, null)
            canvas.drawBitmap(glyph, null, glyphAt, null)

            PuppetRecorder.contentSize = width to height
            PuppetRecorder.markerRect = markerAt
            PuppetRecorder.glyphRect = glyphAt
        }
    }
}
