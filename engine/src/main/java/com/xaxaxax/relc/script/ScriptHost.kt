package com.xaxaxax.relc.script

import androidx.annotation.Keep
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.engine.state.EngineStateRepository
import timber.log.Timber

/**
 * 原生引擎唯一的 upcall 對象：`input.*` / `app.*` / `device.*` / `data.*` 的實作全在這裡。
 *
 * 這樣切的原因是觸控注入的幾何與狀態機已經在 [IRelcV2Service.multiTouchSwipe] 裡了
 * （每個 pointer 一條協程、插值、ACTION_POINTER_DOWN/UP 的 index 計算）。在 C++ 再寫一份
 * 只會有兩份會走樣的實作，所以 native 只留排程、取影格與 OpenCV。
 *
 * 所有方法都在原生的腳本執行緒上被呼叫，而且都是同步的。
 */
@Keep
internal class ScriptHost(
    private val service: IRelcV2Service,
    private val displayId: Int,
    private val onNotify: (title: String, text: String) -> Unit,
    private val onOpenUri: (uri: String) -> Unit,
    private val onData: (key: String, value: Any?) -> Unit,
) {
    /** 記住每個 pointer 最後的位置，這樣 [pointerUp] 才知道要在哪裡放開。 */
    private val lastPoint = HashMap<Int, Pair<Int, Int>>()

    /**
     * @param pointerId -1 代表「隨便給一個」，由服務端配一個內部 id。
     * @param points 攤平的 [x1, y1, x2, y2, ...]。
     * @param keep true 表示結束後保持按住。
     */
    @Keep
    fun swipe(pointerId: Int, points: IntArray, durationMs: Long, keep: Boolean): Boolean {
        if (points.size < 2) return false
        return try {
            service.multiTouchSwipe(pointerId, displayId, points, durationMs, keep)
            if (keep && pointerId >= 0) {
                lastPoint[pointerId] = points[points.size - 2] to points[points.size - 1]
            }
            true
        } catch (t: Throwable) {
            Timber.e(t, "swipe failed")
            false
        }
    }

    @Keep
    fun pointerUp(pointerId: Int): Boolean {
        val (x, y) = lastPoint.remove(pointerId) ?: return false
        return try {
            // 同一個座標、duration 0、keep=false —— 服務端會補上 UP 並清掉 pointer 狀態。
            service.multiTouchSwipe(pointerId, displayId, intArrayOf(x, y), 0L, false)
            true
        } catch (t: Throwable) {
            Timber.e(t, "pointerUp failed")
            false
        }
    }

    @Keep
    fun key(keyCode: Int): Boolean = try {
        val downTime = android.os.SystemClock.uptimeMillis()
        service.injectKeyEvent(
            android.view.KeyEvent(
                downTime, downTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0
            ), displayId
        )
        val upTime = android.os.SystemClock.uptimeMillis()
        service.injectKeyEvent(
            android.view.KeyEvent(
                downTime, upTime, android.view.KeyEvent.ACTION_UP, keyCode, 0
            ), displayId
        )
        true
    } catch (t: Throwable) {
        Timber.e(t, "key($keyCode) failed")
        false
    }

    @Keep
    fun launch(packageName: String): Boolean = try {
        service.launchInDisplay(packageName, displayId)
    } catch (t: Throwable) {
        Timber.e(t, "launch($packageName) failed")
        false
    }

    @Keep
    fun notify(title: String, text: String) = onNotify(title, text)

    @Keep
    fun openUri(uri: String) = onOpenUri(uri)

    @Keep
    fun setData(key: String, value: Any?) = onData(key, value)

    /** 由 C++ 的 pushEvent 呼叫，型別對應 [com.xaxaxax.relc.engine.state.EngineEventType]。 */
    @Keep
    fun onEngineEvent(type: Int, payload: String) {
        EngineStateRepository.onEvent(type, payload)
    }

    /** 腳本結束時清掉還按著的 pointer，避免把觸控卡在按下狀態。 */
    fun releaseAllPointers() {
        lastPoint.keys.toList().forEach { pointerUp(it) }
    }
}
