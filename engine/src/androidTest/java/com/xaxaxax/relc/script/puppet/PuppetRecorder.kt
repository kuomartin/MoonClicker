package com.xaxaxax.relc.script.puppet

import android.graphics.Rect
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PuppetActivity] 看到什麼，就記在這裡。
 *
 * 這是個 process-wide 的 object，而這正是它能存在的原因：library 的 androidTest APK 是
 * 自我 instrument 的，所以 puppet 與測試程式碼**在同一個進程**——測試直接讀這裡，不需要
 * 任何 IPC、不需要第二個 APK、不需要 UiAutomator 去畫面上撈文字。
 *
 * 每個測試在 setUp 時 [reset]。
 */
internal object PuppetRecorder {

    data class Touch(val action: Int, val x: Float, val y: Float, val displayId: Int)

    val touches = CopyOnWriteArrayList<Touch>()
    val keys = CopyOnWriteArrayList<Int>()

    /** puppet 目前活在哪個顯示器上；-1 表示還沒 resume。 */
    @Volatile
    var resumedOnDisplay: Int = -1

    /** [PuppetMarker] 實際被畫在 view 座標的哪裡。`vision.*` 回的座標應該落在這裡面。 */
    @Volatile
    var markerRect: Rect? = null

    /** puppet 的 content view 實際多大——用來確認它真的鋪滿了那個顯示器。 */
    @Volatile
    var contentSize: Pair<Int, Int>? = null

    fun reset() {
        touches.clear()
        keys.clear()
        resumedOnDisplay = -1
        markerRect = null
        contentSize = null
    }

    /** 等到 puppet 在 [displayId] 上 resume 並完成第一次 layout。 */
    fun awaitReady(displayId: Int, timeoutMs: Long = 10_000): Boolean =
        await(timeoutMs) { resumedOnDisplay == displayId && markerRect != null }

    fun awaitTouch(timeoutMs: Long = 5_000, predicate: (Touch) -> Boolean): Touch? {
        await(timeoutMs) { touches.any(predicate) }
        return touches.firstOrNull(predicate)
    }

    /** 輪詢而不是用 latch：條件是好幾個獨立欄位的組合，用 latch 反而要多一層狀態機。 */
    private inline fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
