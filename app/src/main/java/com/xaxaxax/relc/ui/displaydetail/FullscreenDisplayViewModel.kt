package com.xaxaxax.relc.ui.displaydetail

import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class FullscreenDisplayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    data class UiState(
        val isReadOnly: Boolean = false,
        val menuExpanded: Boolean = false,
        val menuOffsetX: Float = 0f,
        val menuOffsetY: Float = 0f,
        val showAppList: Boolean = false,
        val apps: List<AppEntry> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val serviceFlow = UserService.create(
        viewModelScope,
        RelcV2Service::class,
        IRelcV2Service.Stub::asInterface
    )

    init {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                // 初始化 InputController，這會讓 UI 顯示 VirtualDisplaySurfaceView
                _inputController.value = InputController(service)
            }.onFailure {
                Timber.e(it, "Failed to initialize InputController")
            }
        }
    }

    private val _inputController = MutableStateFlow<InputController?>(null)
    val inputController: StateFlow<InputController?> = _inputController.asStateFlow()

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
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun closeAppList() {
        _uiState.value = _uiState.value.copy(showAppList = false)
    }

    fun launchApp(packageName: String, displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                service.launchInDisplay(packageName, displayId)
            }
            closeAppList()
        }
    }

    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                service.destroyVirtualDisplay(displayId)
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private val surfaceHandleMap = mutableMapOf<Surface, Int>()

    fun addSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                val handle = service.addVirtualDisplaySurface(displayId, surface)
                if (handle != -1) {
                    surfaceHandleMap[surface] = handle
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun removeSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                surfaceHandleMap.remove(surface)?.let { handle ->
                    service.removeVirtualDisplaySurface(displayId, handle)
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _inputController.value = null
    }
}
