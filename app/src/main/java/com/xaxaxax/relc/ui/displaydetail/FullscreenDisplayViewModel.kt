package com.xaxaxax.relc.ui.displaydetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FullscreenUiState(
    val isReadOnly: Boolean = false,
    val menuExpanded: Boolean = false,
    val menuOffsetX: Float = 0f,
    val menuOffsetY: Float = 0f,
    val showAppList: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val isControllerReady: Boolean = false
)

@HiltViewModel
class FullscreenDisplayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val displayId: Int = savedStateHandle.get<Int>("displayId") ?: -1

    private val _uiState = MutableStateFlow(FullscreenUiState())
    val uiState: StateFlow<FullscreenUiState> = _uiState.asStateFlow()

    private val serviceFlow = UserService.create(
        viewModelScope,
        RelcShizukuService::class,
        IRelcShizukuService.Stub::asInterface
    )

    private val _controller = MutableStateFlow<VirtualDisplayController?>(null)
    val controller: StateFlow<VirtualDisplayController?> = _controller.asStateFlow()

    private val _inputController = MutableStateFlow<InputController?>(null)
    val inputController: StateFlow<InputController?> = _inputController.asStateFlow()

    init {
        if (displayId != -1) {
            viewModelScope.launch {
                serviceFlow.runWhenAlive { service ->
                    val ctrl = VirtualDisplayController(service)
                    ctrl.attach(displayId)
                    _controller.value = ctrl
                    _inputController.value = InputController(service)
                    _uiState.value = _uiState.value.copy(isControllerReady = true)
                }
            }
        }

        // Initialize isReadOnly from intent/savedState
        val initialReadOnly = savedStateHandle.get<Boolean>("isReadOnly") ?: false
        _uiState.value = _uiState.value.copy(isReadOnly = initialReadOnly)
    }

    fun toggleReadOnly() {
        _uiState.value = _uiState.value.copy(isReadOnly = !_uiState.value.isReadOnly)
    }

    fun setMenuExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(menuExpanded = expanded)
    }

    fun updateMenuOffset(dragAmountX: Float, dragAmountY: Float) {
        _uiState.value = _uiState.value.copy(
            menuOffsetX = _uiState.value.menuOffsetX + dragAmountX,
            menuOffsetY = _uiState.value.menuOffsetY + dragAmountY
        )
    }

    fun openAppList() {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val rawApps = service.launcherApps
                val appEntries = rawApps.map {
                    val parts = it.split("|")
                    AppEntry(parts[0], parts.getOrElse(1) { parts[0] })
                }.sortedBy { it.label }
                _uiState.value = _uiState.value.copy(apps = appEntries, showAppList = true)
            }
        }
    }

    fun closeAppList() {
        _uiState.value = _uiState.value.copy(showAppList = false)
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                service.launchInDisplay(packageName, displayId)
            }
            closeAppList()
        }
    }

    fun destroyDisplay() {
        _controller.value?.destroy()
    }

    override fun onCleared() {
        super.onCleared()
        _controller.value = null
        _inputController.value = null
    }
}
