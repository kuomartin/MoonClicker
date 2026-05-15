package com.xaxaxax.relc.ui

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.H264EncoderSink
import com.xaxaxax.relc.display.NoOpSink
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.script.ScriptEngine
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

data class DebugUiState(
    val controllerState: VirtualDisplayController.State = VirtualDisplayController.State.IDLE,
    val displayId: Int = -1,
    val showSurface: Boolean = false,
    val log: List<String> = emptyList(),
) {
    val canCreate get() = controllerState == VirtualDisplayController.State.IDLE
    val canDestroy get() = controllerState == VirtualDisplayController.State.CREATED
}

@HiltViewModel
class VirtualDisplayDebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState = _uiState.asStateFlow()

    // Compose 透過此 Flow 來建立 SurfaceView binding
    private val serviceFlow by lazy {
        UserService.create(
            viewModelScope,
            RelcV2Service::class,
            IRelcV2Service.Stub::asInterface
        )
    }
    private val _controllerState = MutableStateFlow<VirtualDisplayController?>(null)
    val controllerState = _controllerState.asStateFlow()
    private var controller: VirtualDisplayController?
        get() = _controllerState.value
        set(value) {
            _controllerState.value = value
        }


    val defaultConfig by lazy {
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi
        DisplayConfig(
            name = "ReLC-Debug",
            width = width,
            height = height,
            densityDpi = density,
        )
    }
    var inputController: InputController? = null
        private set

    // ─── Actions ──────────────────────────────────────────────────────────────

    fun createWithNoOp() = viewModelScope.launch {
        serviceFlow.runWhenAlive { service ->
            val ctrl = VirtualDisplayController(service)
            ctrl.create(defaultConfig, NoOpSink)
            controller = ctrl
            inputController = InputController(service)
            log("Created VD with NoOpSink, displayId=${ctrl.displayId}")
            syncState(ctrl, showSurface = false)
        }
    }

    /**
     * 用 NoOpSink 建立 VD，然後顯示 SurfaceView。
     * SurfaceView ready 後 VirtualDisplaySurfaceView 會自動 replaceSink(DirectSink)。
     */
    fun createWithDirectSink() = viewModelScope.launch {
        serviceFlow.runWhenAlive { service ->
            val ctrl = VirtualDisplayController(service)
            ctrl.create(defaultConfig, NoOpSink)   // 先用 NoOpSink，等 Surface 準備好再換
            controller = ctrl
            inputController = InputController(service)
            log("Created VD (waiting for SurfaceView...), displayId=${ctrl.displayId}")
            syncState(ctrl, showSurface = true)
        }
    }

    fun createWithH264() = viewModelScope.launch {
        serviceFlow.runWhenAlive { service ->
            val ctrl = VirtualDisplayController(service)
            val h264Sink = H264EncoderSink(defaultConfig) { buffer, info ->
                // 這裡處理編碼後的數據，例如：
                // 1. 透過 WebSocket 發送給前端
                // 2. 寫入檔案
                log("Encoded frame size: ${info.size}")
                // TODO: LocalSocket send
            }
            ctrl.create(defaultConfig, h264Sink)
            controller = ctrl
            inputController = InputController(service)
            log("Created VD with H264EncoderSink")
            syncState(ctrl, showSurface = false) // H264 模式通常不需要在手機端預覽 SurfaceView
        }
    }


    fun destroy() = runCatching {
        controller?.let { ctrl ->
            ctrl.destroy()
            log("Destroyed VD")
            controller = null
            syncState(null, false)
        }
    }.onFailure { log("destroy error: ${it.message}") }

    fun openApp(packageName: String, displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val result = service.launchInDisplay(packageName, displayId)

                Timber.d("openApp: $result")
            }
        }
    }

    fun testInput(displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val ic = InputController(service)
                viewModelScope.launch {
                    log("Testing input on display $displayId...")
                    delay(1000.milliseconds)
                    ic.tap(500, 500, 500, displayId)
                    delay(500.milliseconds)
                    ic.swipePolyline(500, listOf(200 to 1000, 800 to 1000), displayId)
                    log("Input test done")
                }
            }
        }
    }

    fun runTestScript(displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val engine = ScriptEngine(service) { msg -> log(msg) }
                val script = """
                log("Script started on display $displayId")
                display.launch("moe.shizuku.privileged.api", $displayId)
                sleep(2000)
                log("Tapping...")
                displayId = $displayId
                input.tap(50, 500, 500)
                sleep(1000)
                log("Swiping...")
                input.swipe(500, 200, 1500, 200, 500)
                log("Script finished")
            """.trimIndent()

                viewModelScope.launch {
                    runCatching {
                        engine.execute(script)
                    }.onFailure {
                        log("Script Error: ${it.message}")
                    }
                }
            }
        }
    }

    fun debug(input: String) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val result = service.debug(input)
                Timber.d("Debug: $result")
            }
        }
    }


    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun syncState(ctrl: VirtualDisplayController?, showSurface: Boolean) {
        _uiState.update {
            it.copy(
                controllerState = ctrl?.state ?: VirtualDisplayController.State.IDLE,
                displayId = ctrl?.displayId ?: -1,
                showSurface = showSurface
            )
        }
    }

    private fun log(msg: String) {
        Timber.d("[Debug] $msg")
        _uiState.update { it.copy(log = it.log + msg) }
    }
}
