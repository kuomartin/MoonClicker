package com.xaxaxax.moonclicker.engine

import android.content.Context
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import com.xaxaxax.moonclicker.lua.LuaNative
import com.xaxaxax.moonclicker.script.ScriptHost
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
     * 是否掛影格來源。只有 [IMoonClickerService] 自己建立的虛擬顯示拿得到影格，
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

    private val _logLines = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** 腳本透過 Lua 的 `log(...)` 送出的每一行。 */
    val logLines: SharedFlow<String> = _logLines.asSharedFlow()

    private var host: ScriptHost? = null

    val isRunning: Boolean get() = LuaNative.nativeIsRunning()

    /**
     * 啟動一份腳本。同一時間只能有一份在跑，已有腳本執行中會回傳 false。
     *
     * 顯示器的生命週期**不屬於**引擎：這裡只會把取影格用的 surface 掛上去，結束時再拿掉，
     * 顯示器本身留給 :app 的 Displays 頁處置。
     */
    fun start(
        context: Context,
        service: IMoonClickerService,
        run: ScriptRun,
        onNotify: (title: String, text: String) -> Unit,
        onOpenUri: (uri: String) -> Unit,
    ): Boolean {
        if (isRunning) {
            Timber.w("start(${run.scriptId}) rejected: another script is running")
            return false
        }

        // AImageReader 開成 distributor 目前輸出的影格尺寸（ADR-0017：轉正後的自然尺寸，
        // 已經隨 v 互換長寬）——問服務要這個值，不自己從（邏輯尺寸, rotation）回推。
        // 緩衝區一開就是整場執行，中途 VD 再旋轉不會跟著變，見 ADR-0017 的 Consequences。
        val surface = try {
            service.getDisplaySurfaceSize(run.displayId)
        } catch (t: Throwable) {
            Timber.e(t, "getDisplaySurfaceSize(${run.displayId}) failed")
            null
        }
        if (surface == null || surface.size < 2 || surface[0] <= 0 || surface[1] <= 0) {
            // [0, 0] 也代表「這個 id 不是服務建的顯示器」。與其用推出來的幾何硬跑，
            // 不如當場停下來講清楚。
            EngineStateRepository.onEvent(
                com.xaxaxax.moonclicker.engine.state.EngineEventType.ERROR,
                "Could not read the surface size of display ${run.displayId}",
            )
            return false
        }
        val (surfaceWidth, surfaceHeight) = surface[0] to surface[1]

        val rotation = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(run.displayId)?.rotation ?: 0

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
            // native 執行緒同步呼叫進來，不是 suspend context，emit 會掛住呼叫端。
            onLog = { line -> _logLines.tryEmit(line) },
        )
        host = scriptHost

        val displayInfo = runCatching { service.getDisplayInfo(run.displayId) }.getOrNull()
        val isPhysical = displayInfo?.isPhysical ?: (run.displayId == 0)

        val started = LuaNative.nativeStart(
            scriptHost,
            service,
            run.displayId,
            isPhysical,
            run.hasVision,
            surfaceWidth,
            surfaceHeight,
            // 純粹給 Lua screen.rotation 讀的中繼資料，不影響任何座標換算，見 nativeStart 的文件。
            rotation,
            run.scriptDir.absolutePath,
        )
        if (!started) {
            host = null
            EngineStateRepository.onEvent(
                com.xaxaxax.moonclicker.engine.state.EngineEventType.ERROR,
                "Native engine refused to start ${run.scriptDir.name}",
            )
            return false
        }

        return true
    }

    fun stop() {
        LuaNative.nativeStop()
        // native 已經停了，收尾放開任何還按著的觸控，別把目標 app 卡在按下狀態。
        host?.releaseAllPointers()
        host = null
    }
}
