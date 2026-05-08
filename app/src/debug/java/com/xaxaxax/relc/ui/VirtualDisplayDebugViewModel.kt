package com.xaxaxax.relc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.NoOpSink
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.shizuku.InputManager
import com.xaxaxax.relc.shizuku.ShizukuUserService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

data class DebugUiState(
    val controllerState: VirtualDisplayController.State = VirtualDisplayController.State.IDLE,
    val displayId: Int = -1,
    val showSurface: Boolean = false,
    val log: List<String> = emptyList(),
) {
    val canCreate get() = controllerState == VirtualDisplayController.State.IDLE
    val canLaunch get() = controllerState == VirtualDisplayController.State.CREATED
    val canDestroy get() = controllerState == VirtualDisplayController.State.CREATED
}

class VirtualDisplayDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState = _uiState.asStateFlow()

    private var serviceHandle: ShizukuUserService.Handle<IRelcShizukuService>? = null

    // Compose 透過此 StateFlow 取得 controller 來建立 SurfaceView binding
    private val _controllerState = MutableStateFlow<VirtualDisplayController?>(null)
    val controllerState = _controllerState.asStateFlow()
    private var controller: VirtualDisplayController?
        get() = _controllerState.value
        set(value) {
            _controllerState.value = value
        }


    private val defaultConfig = DisplayConfig(
        name = "ReLC-Debug",
        width = 1080,
        height = 1920,
        densityDpi = 320,
    )

    // ─── Actions ──────────────────────────────────────────────────────────────

    fun createWithNoOp() = withService { service ->
        val ctrl = VirtualDisplayController(service)
        ctrl.create(defaultConfig, NoOpSink())
        controller = ctrl
        log("Created VD with NoOpSink, displayId=${ctrl.displayId}")
        syncState(ctrl, showSurface = false)
    }

    /**
     * 用 NoOpSink 建立 VD，然後顯示 SurfaceView。
     * SurfaceView ready 後 VirtualDisplaySurfaceView 會自動 replaceSink(DirectSink)。
     */
    fun createWithDirectSink() = withService { service ->
        val ctrl = VirtualDisplayController(service)
        ctrl.create(defaultConfig, NoOpSink())   // 先用 NoOpSink，等 Surface 準備好再換
        controller = ctrl
        log("Created VD (waiting for SurfaceView...), displayId=${ctrl.displayId}")
        syncState(ctrl, showSurface = true)
    }

    fun createWithH264() {
        log("H264Sink not implemented yet")
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
        withService { service ->
            val result = service.launchInDisplay(packageName, displayId)

            Timber.d("openApp: $result")
        }
    }

    fun testInput(displayId: Int) {
        val im = InputManager

        viewModelScope.launch {
            delay(500.milliseconds)
            im.touchDown(
                500, 700, 0, displayId
            )
            delay(500.milliseconds)
            im.touchMove(
                1000, 700, 0, displayId
            )
            delay(500.milliseconds)
            im.touchUp(
                100, 1000, 0, displayId
            )
        }
    }


    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun withService(block: (IRelcShizukuService) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val handle = serviceHandle ?: run {
                    val ctx = getApplication<Application>()
                    // 使用 lastUpdateTime 作為版本：每次重裝都會改變，
                    // 即使 versionCode 沒有更新也會讓 Shizuku 重啟 UserService 進程。
                    val installVersion = ctx.packageManager
                        .getPackageInfo(ctx.packageName, 0)
                        .lastUpdateTime
                        .toInt()
                    ShizukuUserService.connect<IRelcShizukuService>(
                        serviceClass = RelcShizukuService::class,
                        asInterface = IRelcShizukuService.Stub::asInterface,
                        version = installVersion,
                    )
                }.also { serviceHandle = it }
                block(handle.service)
            }.onFailure {
                Timber.e(it)
                log("Error: ${it.message}")
            }
            syncState(controller, uiState.value.showSurface)
        }
    }

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
