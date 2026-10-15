package com.xaxaxax.moonclicker.script

import android.view.Surface
import androidx.annotation.Keep
import com.xaxaxax.moonclicker.DisplaySink
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.MoonClickerAppTask
import timber.log.Timber

/**
 * 原生引擎唯一的 upcall 對象：`input.*` / `app.*` / `ui.*` / `device.*` / `data.*` 與 `screen` 的鏡像控制
 * 的實作全在這裡。本腳本 `screen.start_mirror()` 取得的鏡像引用也由它持有，[close] 時歸還。
 *
 * 這樣切的原因是觸控注入的幾何與狀態機已經在 [IMoonClickerService.multiTouchSwipe] 裡了
 * （每個 pointer 一條協程、插值、ACTION_POINTER_DOWN/UP 的 index 計算）。在 C++ 再寫一份
 * 只會有兩份會走樣的實作，所以 native 只留排程、取影格與 OpenCV。
 *
 * 所有方法都在原生的腳本執行緒上被呼叫，而且都是同步的。
 *
 * 失敗分兩類（ADR-0021）：基礎設施失敗（Binder 斷線、服務端拋例外）讓例外穿出去，由 native 轉成
 * Lua error；操作本身沒成功（服務回 false）就回 false。所以這裡不 catch。
 */
@Keep
internal class ScriptHost(
    private val service: IMoonClickerService,
    private val displayId: Int,
    private val onNotify: (title: String, text: String) -> Unit,
    private val onOpenUri: (uri: String) -> Unit,
    private val onData: (key: String, value: Any?) -> Unit,
    private val onLog: (line: String) -> Unit,
    private val onEvent: (type: Int, payload: String) -> Unit,
    /** REPL 模式下一段程式碼的結果；腳本模式不會被呼叫。 */
    private val onReplResult: (id: Long, status: Int, output: String) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    /** 記住每個 pointer 最後的位置，這樣 [pointerUp] 才知道要在哪裡放開。 */
    private val lastPoint = HashMap<Int, Pair<Int, Int>>()

    /** 這支腳本是否持有一份鏡像引用。只歸還自己拿的那一份，別人開的鏡像不碰。 */
    private var heldMirror = false

    /**
     * @param pointerId -1 代表「隨便給一個」，由服務端配一個內部 id。
     * @param points 攤平的 [x1, y1, x2, y2, ...]，native 端保證至少一個點。
     * @param keep true 表示結束後保持按住。
     */
    @Keep
    fun swipe(pointerId: Int, points: IntArray, durationMs: Long, keep: Boolean) {
        service.multiTouchSwipe(pointerId, displayId, points, durationMs, keep)
        if (keep && pointerId >= 0) {
            lastPoint[pointerId] = points[points.size - 2] to points[points.size - 1]
        }
    }

    /** 沒按下的 pointer 直接略過——那不是基礎設施失敗。 */
    @Keep
    fun pointerUp(pointerId: Int) {
        val (x, y) = lastPoint.remove(pointerId) ?: return
        // 同一個座標、duration 0、keep=false —— 服務端會補上 UP 並清掉 pointer 狀態。
        service.multiTouchSwipe(pointerId, displayId, intArrayOf(x, y), 0L, false)
    }

    /** DOWN 被拒就不送 UP。 */
    @Keep
    fun key(keyCode: Int): Boolean {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!service.injectKeyEvent(down, displayId)) return false
        val up = android.view.KeyEvent(
            downTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, keyCode, 0
        )
        return service.injectKeyEvent(up, displayId)
    }

    /**
     * 字元已在 native 端檢查過（可印 ASCII、`\n`、`\t`），VIRTUAL_KEYBOARD 都對得到；`getEvents`
     * 還是回 null 就是環境出了問題。事件自帶 Shift 的 down/up。中途注入失敗時前面的字已經送出，無法回滾。
     */
    @Keep
    fun text(text: String): Boolean {
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray())
            ?: throw IllegalStateException("no key events for \"$text\"")
        return events.all { service.injectKeyEvent(it, displayId) }
    }

    @Keep
    fun launch(packageName: String): Boolean = service.launchInDisplay(packageName, displayId)

    /** 空陣列是真的沒有 app。 */
    @Keep
    fun listApps(): Array<MoonClickerAppTask> = service.appTasks

    @Keep
    fun mute(packageName: String, muted: Boolean): Boolean = service.setAppMuted(packageName, muted)

    /** UTF-8 位元組而不是字串：JNI 的 modified UTF-8 會弄壞 emoji 這類 BMP 以外的字。 */
    @Keep
    fun viewTree(): ByteArray = service.getViewTree(displayId).toByteArray()

    @Keep
    fun notify(title: String, text: String) = onNotify(title, text)

    @Keep
    fun openUri(uri: String) = onOpenUri(uri)

    @Keep
    fun setData(key: String, value: Any?) = onData(key, value)

    @Keep
    fun log(line: String) = onLog(line)

    /**
     * vision 取影格用的 sink；掛不上回 null，由 native 端關掉 vision。這裡照舊吞例外：
     * 腳本啟動時是在呼叫端的執行緒上呼叫，還沒有 Lua 堆疊可以拋錯。
     */
    @Keep
    fun attachSink(surface: Surface): DisplaySink? = try {
        DisplaySink(displayId, surface).takeIf { it.attach(service) }
    } catch (t: Throwable) {
        Timber.e(t, "attachSink failed")
        null
    }

    /** 已持有時直接回 true，不重複取引用。 */
    @Keep
    fun acquireMirror(): Boolean {
        if (!heldMirror) heldMirror = service.acquireDisplayMirror(displayId)
        return heldMirror
    }

    /** 沒持有回 false。先清旗標再歸還：服務端失敗時引用也不能留在這裡等下次再試。 */
    @Keep
    fun releaseMirror(): Boolean {
        if (!heldMirror) return false
        heldMirror = false
        service.releaseDisplayMirror(displayId)
        return true
    }

    /** 持有時不必走 Binder：`input.*` 在實體螢幕上每次都會問。 */
    @Keep
    fun isMirrorActive(): Boolean = heldMirror || service.isDisplayMirrorActive(displayId)

    /** 由 C++ 的 pushEvent 呼叫，型別對應 [com.xaxaxax.moonclicker.engine.state.EngineEventType]。 */
    @Keep
    fun onEngineEvent(type: Int, payload: String) {
        onEvent(type, payload)
    }

    /** [output] 是 UTF-8 bytes，見 native 的 reportReplResult。 */
    @Keep
    fun replResult(id: Long, status: Int, output: ByteArray) {
        onReplResult(id, status, output.decodeToString())
    }

    /**
     * 腳本結束時放開還按著的 pointer、歸還鏡像，不把觸控卡在按下狀態，也不讓鏡像一直開著。
     * 這時已經沒有 Lua 堆疊可以拋錯，每一項各自吞掉失敗，不擋住後面的清理。
     */
    override fun close() {
        lastPoint.keys.toList().forEach { pointerId ->
            runCatching { pointerUp(pointerId) }.onFailure { Timber.e(it, "pointerUp($pointerId) failed") }
        }
        runCatching { releaseMirror() }.onFailure { Timber.e(it, "releaseMirror failed") }
    }
}
