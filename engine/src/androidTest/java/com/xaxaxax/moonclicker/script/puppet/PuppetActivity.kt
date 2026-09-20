package com.xaxaxax.moonclicker.script.puppet

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
 * 被腳本操作的那個 app：把 [PuppetMarker] 畫在已知位置，並把收到的每一個觸控與按鍵記進
 * [PuppetRecorder]，讓「vision 找到的位置」與「tap 真的打到的位置」互相印證。
 *
 * 宣告在 `engine/src/androidTest/AndroidManifest.xml`，套件名即測試 APK 的
 * `com.xaxaxax.moonclicker.engine.test`。
 */
class PuppetActivity : Activity() {

    companion object {
        @Volatile
        private var instance: PuppetActivity? = null

        /**
         * 讓 puppet 宣告一個方向，藉此把它所在的虛擬顯示轉過去——app 宣告的方向會贏過
         * `setDisplayRotation` 設的 user rotation，所以從外面轉不動。
         *
         * @param orientation `ActivityInfo.SCREEN_ORIENTATION_*`
         */
        fun requestOrientation(orientation: Int) {
            val activity = instance ?: return
            activity.runOnUiThread { activity.requestedOrientation = orientation }
        }

        /** 立刻改變畫的東西。 */
        fun setVisible(marker: Boolean, glyph: Boolean) {
            PuppetControl.markerVisible = marker
            PuppetControl.glyphVisible = glyph
            val activity = instance ?: return
            activity.runOnUiThread { activity.markerView.invalidate() }
        }

        /** [delayMs] 之後才把東西畫出來，讓 `vision.wait` 的等待語意真的被執行到。 */
        fun showAfter(delayMs: Long, marker: Boolean = true, glyph: Boolean = true) {
            val activity = instance ?: return
            activity.runOnUiThread {
                activity.markerView.postDelayed({
                    PuppetControl.markerVisible = marker
                    PuppetControl.glyphVisible = glyph
                    activity.markerView.invalidate()
                }, delayMs)
            }
        }

        /**
         * 測試之間一定要呼叫：虛擬顯示被銷毀時 activity 會被搬回預設顯示器，下一個測試的
         * `app.launch` 就只是把那個 task 撈回前景，新顯示器上什麼都沒有。
         */
        fun finishAndWait(timeoutMs: Long = 5_000) {
            val activity = instance ?: return
            activity.runOnUiThread { activity.finishAndRemoveTask() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (instance != null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
    }

    private lateinit var markerView: MarkerView
        private set

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
     * 全黑底 + 一塊 [PuppetMarker]，刻意不放正中央——放中央的話，
     * 「找到了」與「座標換算全錯但剛好對稱」看起來一模一樣。
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
            // 擺在另一個象限，離對稱標記遠一點——兩個都是黑白幾何圖樣，靠太近會互相比中。
            val glyphAt = Rect(
                width / 2,
                height * 2 / 3,
                width / 2 + PuppetGlyph.SIZE,
                height * 2 / 3 + PuppetGlyph.SIZE,
            )

            // 指定目的矩形而不是 drawBitmap(bmp, x, y, paint)：後者會做密度縮放，把標記畫成
            // 與模板 PNG 不同的大小，而 TM_CCOEFF_NORMED 不是尺度不變的。
            if (PuppetControl.markerVisible) canvas.drawBitmap(marker, null, markerAt, null)
            if (PuppetControl.glyphVisible) canvas.drawBitmap(glyph, null, glyphAt, null)

            // 即使這一輪沒畫也照樣發佈：矩形描述的是「會被畫在哪」，awaitReady 靠它判斷
            // 版面完成了沒。
            PuppetRecorder.contentSize = width to height
            PuppetRecorder.markerRect = markerAt
            PuppetRecorder.glyphRect = glyphAt
        }
    }
}
