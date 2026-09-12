package com.xaxaxax.relc.engine

import android.content.Context
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.engine.state.EngineStateRepository
import com.xaxaxax.relc.lua.LuaNative
import com.xaxaxax.relc.script.DisplayGeometry
import com.xaxaxax.relc.script.DisplayRotationTracker
import com.xaxaxax.relc.script.ScriptHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File

/** 一次執行要跑的東西：哪份腳本、跑在哪個顯示器上。 */
data class ScriptRun(
    /** 給 UI 對照用的識別，[EngineStateRepository] 會把它一起帶著。 */
    val scriptId: String,
    /** 腳本資料夾，內含 `main.lua`；模板路徑相對於它解析。 */
    val scriptDir: File,
    /** 目標顯示器。0 是實體螢幕。 */
    val displayId: Int,
    /**
     * 是否掛影格來源。只有 [IRelcV2Service] 自己建立的虛擬顯示拿得到影格，
     * 實體螢幕必須是 false——那時 `vision.*` 會在 Lua 端明確報錯。
     */
    val hasVision: Boolean,
)

/**
 * `:engine` 對外的腳本執行門面。其他模組只透過這裡啟停引擎，不碰 `LuaNative`
 * （它是 `internal`，編譯器會擋）。
 *
 * 執行狀態不要在這裡輪詢——觀察 [EngineStateRepository.state]。
 */
object ScriptEngine {

    private val _sharedData = MutableStateFlow<Map<String, Any>>(emptyMap())

    /** 腳本透過 Lua 的 `data.set` 發佈的鍵值。 */
    val sharedData: StateFlow<Map<String, Any>> = _sharedData.asStateFlow()

    private var host: ScriptHost? = null
    private var rotationTracker: DisplayRotationTracker? = null

    val isRunning: Boolean get() = LuaNative.nativeIsRunning()

    /**
     * 啟動一份腳本。同一時間只能有一份在跑，已有腳本執行中會回傳 false。
     *
     * 顯示器的生命週期**不屬於**引擎：這裡只會把取影格用的 surface 掛上去，結束時再拿掉，
     * 顯示器本身留給 :app 的 Displays 頁處置。
     */
    fun start(
        context: Context,
        service: IRelcV2Service,
        run: ScriptRun,
        onNotify: (title: String, text: String) -> Unit,
        onOpenUri: (uri: String) -> Unit,
    ): Boolean {
        if (isRunning) {
            Timber.w("start(${run.scriptId}) rejected: another script is running")
            return false
        }

        val size = try {
            service.getDisplaySize(run.displayId)
        } catch (t: Throwable) {
            Timber.e(t, "getDisplaySize(${run.displayId}) failed")
            null
        }
        if (size == null || size.size < 2 || size[0] <= 0 || size[1] <= 0) {
            EngineStateRepository.onEvent(
                com.xaxaxax.relc.engine.state.EngineEventType.ERROR,
                "Could not read the size of display ${run.displayId}",
            )
            return false
        }

        // getDisplaySize 回的是**邏輯**尺寸（Display.getRealSize，已套用旋轉），但 AImageReader
        // 必須以虛擬顯示建立時的 surface 尺寸開。旋轉 90/270 時兩者長寬互換——用錯的話影格
        // 會被擠進錯誤長寬比的緩衝區，比對與座標全歪。
        val rotation = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(run.displayId)?.rotation ?: 0
        val (surfaceWidth, surfaceHeight) =
            DisplayGeometry.surfaceSize(size[0], size[1], rotation)

        _sharedData.value = emptyMap()
        EngineStateRepository.reset(run.scriptId)

        val scriptHost = ScriptHost(
            service = service,
            displayId = run.displayId,
            onNotify = onNotify,
            onOpenUri = onOpenUri,
            onData = { key, value ->
                _sharedData.update { current ->
                    if (value == null) current - key else current + (key to value)
                }
            },
        )
        host = scriptHost

        val started = LuaNative.nativeStart(
            scriptHost,
            service,
            run.displayId,
            run.hasVision,
            surfaceWidth,
            surfaceHeight,
            // 初始 rotation 隨啟動一起傳進去。交給下面的 tracker 才推的話，腳本的第一行
            // 有機會在 rotation 還沒設定時就讀到 screen.width。
            rotation,
            run.scriptDir.absolutePath,
        )
        if (!started) {
            host = null
            EngineStateRepository.onEvent(
                com.xaxaxax.relc.engine.state.EngineEventType.ERROR,
                "Native engine refused to start ${run.scriptDir.name}",
            )
            return false
        }

        rotationTracker = DisplayRotationTracker(context).also { it.start(run.displayId) }
        return true
    }

    fun stop() {
        rotationTracker?.stop()
        rotationTracker = null
        LuaNative.nativeStop()
        // native 已經停了，收尾放開任何還按著的觸控，別把目標 app 卡在按下狀態。
        host?.releaseAllPointers()
        host = null
    }
}
