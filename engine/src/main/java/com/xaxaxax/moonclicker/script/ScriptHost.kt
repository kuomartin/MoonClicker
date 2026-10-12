package com.xaxaxax.moonclicker.script

import android.view.Surface
import androidx.annotation.Keep
import com.xaxaxax.moonclicker.DisplaySink
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.MoonClickerAppTask
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import timber.log.Timber

/**
 * 原生引擎唯一的 upcall 對象：`input.*` / `app.*` / `device.*` / `data.*` 與 `screen` 的鏡像控制
 * 的實作全在這裡。本腳本 `screen.start_mirror()` 取得的鏡像引用也由它持有，[close] 時歸還。
 *
 * 這樣切的原因是觸控注入的幾何與狀態機已經在 [IMoonClickerService.multiTouchSwipe] 裡了
 * （每個 pointer 一條協程、插值、ACTION_POINTER_DOWN/UP 的 index 計算）。在 C++ 再寫一份
 * 只會有兩份會走樣的實作，所以 native 只留排程、取影格與 OpenCV。
 *
 * 所有方法都在原生的腳本執行緒上被呼叫，而且都是同步的。
 */
@Keep
internal class ScriptHost(
    private val service: IMoonClickerService,
    private val displayId: Int,
    private val onNotify: (title: String, text: String) -> Unit,
    private val onOpenUri: (uri: String) -> Unit,
    private val onData: (key: String, value: Any?) -> Unit,
    private val onLog: (line: String) -> Unit,
) : AutoCloseable {
    /** 記住每個 pointer 最後的位置，這樣 [pointerUp] 才知道要在哪裡放開。 */
    private val lastPoint = HashMap<Int, Pair<Int, Int>>()

    /** 這支腳本是否持有一份鏡像引用。只歸還自己拿的那一份，別人開的鏡像不碰。 */
    private var heldMirror = false

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

    /**
     * 字元已在 native 端檢查過（可印 ASCII、`\n`、`\t`），VIRTUAL_KEYBOARD 都對得到；`getEvents`
     * 回 null 只是防呆。事件自帶 Shift 的 down/up。中途注入失敗時前面的字已經送出，無法回滾。
     */
    @Keep
    fun text(text: String): Boolean = try {
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray())
        if (events == null) {
            Timber.e("text: no key events for \"$text\"")
            false
        } else {
            events.all { service.injectKeyEvent(it, displayId) }
        }
    } catch (t: Throwable) {
        Timber.e(t, "text failed")
        false
    }

    /** 裝置不支援時讓 [UnsupportedOperationException] 穿出去，由 native 端轉成 Lua error。 */
    @Keep
    fun launch(packageName: String): Boolean = try {
        service.launchInDisplay(packageName, displayId)
    } catch (e: UnsupportedOperationException) {
        throw e
    } catch (t: Throwable) {
        Timber.e(t, "launch($packageName) failed")
        false
    }

    /** null 代表列不出來（UserService 異常），由 native 端轉成 Lua error；空陣列是真的沒有 app。 */
    @Keep
    fun listApps(): Array<MoonClickerAppTask>? = try {
        service.appTasks
    } catch (t: Throwable) {
        Timber.e(t, "listApps failed")
        null
    }

    @Keep
    fun mute(packageName: String, muted: Boolean): Boolean = try {
        service.setAppMuted(packageName, muted)
    } catch (t: Throwable) {
        Timber.e(t, "mute($packageName, $muted) failed")
        false
    }

    @Keep
    fun notify(title: String, text: String) = onNotify(title, text)

    @Keep
    fun openUri(uri: String) = onOpenUri(uri)

    @Keep
    fun setData(key: String, value: Any?) = onData(key, value)

    @Keep
    fun log(line: String) = onLog(line)

    /** vision 取影格用的 sink；掛不上回 null，由 native 端關掉 vision。 */
    @Keep
    fun attachSink(surface: Surface): DisplaySink? = try {
        DisplaySink(displayId, surface).takeIf { it.attach(service) }
    } catch (t: Throwable) {
        Timber.e(t, "attachSink failed")
        null
    }

    /** 由 C++ 的 pushEvent 呼叫，型別對應 [com.xaxaxax.moonclicker.engine.state.EngineEventType]。 */
    @Keep
    fun onEngineEvent(type: Int, payload: String) {
        EngineStateRepository.onEvent(type, payload)
    }

    /** 已持有時直接回 true，不重複取引用。 */
    @Keep
    fun acquireMirror(): Boolean {
        if (heldMirror) return true
        heldMirror = try {
            service.acquireDisplayMirror(displayId)
        } catch (t: Throwable) {
            Timber.e(t, "acquireMirror failed")
            false
        }
        return heldMirror
    }

    /** 沒持有回 false。服務端歸還失敗也視為已歸還——引用不能留在這裡等下次再試。 */
    @Keep
    fun releaseMirror(): Boolean {
        if (!heldMirror) return false
        heldMirror = false
        try {
            service.releaseDisplayMirror(displayId)
        } catch (t: Throwable) {
            Timber.e(t, "releaseMirror failed")
        }
        return true
    }

    /** 持有時不必走 Binder：`input.*` 在實體螢幕上每次都會問。 */
    @Keep
    fun isMirrorActive(): Boolean = heldMirror || try {
        service.isDisplayMirrorActive(displayId)
    } catch (t: Throwable) {
        Timber.e(t, "isMirrorActive failed")
        false
    }

    /** 腳本結束時放開還按著的 pointer、歸還鏡像，不把觸控卡在按下狀態，也不讓鏡像一直開著。 */
    override fun close() {
        lastPoint.keys.toList().forEach { pointerUp(it) }
        releaseMirror()
    }
}
