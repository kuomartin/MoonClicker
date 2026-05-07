package com.xaxaxax.relc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.NoOpSink
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.shizuku.ShizukuUserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class DebugUiState(
    val controllerState: VirtualDisplayController.State = VirtualDisplayController.State.IDLE,
    val displayId: Int = -1,
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
    private var controller: VirtualDisplayController? = null

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
        syncState(ctrl)
    }

    fun createWithH264() {
        log("H264Sink not implemented yet")
    }

    fun launch(packageName: String) = withService { service ->
        val id = controller?.displayId ?: return@withService
        val ok = service.launchInDisplay(packageName, id)
        log("launchInDisplay($packageName, $id) → $ok")
    }

    fun destroy() = runCatching {
        controller?.let { ctrl ->
            ctrl.destroy()
            log("Destroyed VD")
            syncState(ctrl)
            controller = null
        }
    }.onFailure { log("destroy error: ${it.message}") }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun withService(block: (IRelcShizukuService) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val handle = serviceHandle ?: ShizukuUserService.connect<IRelcShizukuService>(
                    serviceClass = RelcShizukuService::class,
                    asInterface = IRelcShizukuService.Stub::asInterface,
                ).also { serviceHandle = it }
                block(handle.service)
            }.onFailure {
                Timber.e(it)
                log("Error: ${it.message}")
            }
            syncState(controller)
        }
    }

    private fun syncState(ctrl: VirtualDisplayController?) {
        _uiState.update {
            it.copy(
                controllerState = ctrl?.state ?: VirtualDisplayController.State.IDLE,
                displayId = ctrl?.displayId ?: -1,
            )
        }
    }

    private fun log(msg: String) {
        Timber.d("[Debug] $msg")
        _uiState.update { it.copy(log = it.log + msg) }
    }
}
