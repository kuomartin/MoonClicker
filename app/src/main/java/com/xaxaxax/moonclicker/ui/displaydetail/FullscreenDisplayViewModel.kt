package com.xaxaxax.moonclicker.ui.displaydetail

import android.content.Context
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class FullscreenDisplayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val shizukuManager: ShizukuManager,
    private val thumbnailCache: DisplayThumbnailCache,
    /**
     * workbench 的 `/mirror/{displayId}` 從這裡取畫面（見 #76）。直接把 singleton 露給畫面用，
     * 比照 [cropSession]：登記/解除登記是畫面自己的生命週期事件，再包一層轉呼叫不會更清楚。
     */
    val mirrorFrames: MirrorFrameSource,
) : ViewModel() {

    data class UiState(
        val isReadOnly: Boolean = false,
        val menuExpanded: Boolean = false,
        val menuOffsetX: Float = 0f,
        val menuOffsetY: Float = 0f,
        val showAppList: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 裁切工作流的唯一權威，見 [CropSession]。 */
    val cropSession = CropSession(context)

    /**
     * 綁好的服務，也是 UI 判斷「可以顯示鏡像了沒」的依據——服務在，鏡像才有東西可映。
     *
     * 直接轉發 [ShizukuManager] 的 flow，不另外存一份：多存一份就多一個會跟真實綁定狀態
     * 走樣的地方，而它表達的是同一件事。
     */
    val service: StateFlow<IMoonClickerService?> = shizukuManager.serviceFlow

    fun toggleReadOnly() {
        _uiState.value = _uiState.value.copy(isReadOnly = !_uiState.value.isReadOnly)
    }

    fun startCropping() {
        cropSession.start()
    }

    /**
     * issue #41：退出 fullscreen（不論哪條離開路徑，見呼叫端掛在 `onPause`）時留一張縮圖。
     * 縮放/轉正/寫檔都不是可以卡在 onPause 上的工作，丟到 IO dispatcher 做。
     */
    fun captureThumbnail(displayId: Int, bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            thumbnailCache.put(displayId, bitmap)
        }
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
            shizukuManager.withService { service ->
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
            shizukuManager.withService { service ->
                service.launchInDisplay(packageName, displayId)
            }
            closeAppList()
        }
    }

    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.destroyVirtualDisplay(displayId)
            }.onSuccess {
                thumbnailCache.remove(displayId)
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private val surfaceHandleMap = mutableMapOf<Surface, Int>()

    fun addSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            shizukuManager.withService{ service ->
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
            shizukuManager.withService { service ->
                surfaceHandleMap.remove(surface)?.let { handle ->
                    service.removeVirtualDisplaySurface(displayId, handle)
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

}
