package com.xaxaxax.moonclicker.ui.displaydetail

import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.IMoonClickerService
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 扇形選單四顆按鈕的命令型別，[FullscreenDisplayViewModel.onAction] 的唯一入口分派這些。 */
sealed interface FullscreenAction {
    data object StartApp : FullscreenAction
    data object CloseDisplay : FullscreenAction
    data object Exit : FullscreenAction
    data object Home : FullscreenAction
}

@HiltViewModel
class FullscreenDisplayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val shizukuManager: ShizukuManager,
    private val thumbnailCache: DisplayThumbnailCache,
    /**
     * workbench 的 `/mirror/{displayId}` 從這裡取畫面（見 #76）。直接把 singleton 露給畫面用，
     * 登記/解除登記是畫面自己的生命週期事件，再包一層轉呼叫不會更清楚。
     */
    val mirrorFrames: MirrorFrameSource,
) : ViewModel() {

    data class UiState(
        val showAppList: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 退出全螢幕（[FullscreenAction.Exit]／[FullscreenAction.CloseDisplay]）的一次性事件；畫面收到就 `finish()`。 */
    private val _finishEvents = Channel<Unit>(Channel.BUFFERED)
    val finishEvents: Flow<Unit> = _finishEvents.receiveAsFlow()

    /**
     * 綁好的服務，也是 UI 判斷「可以顯示鏡像了沒」的依據——服務在，鏡像才有東西可映。
     *
     * 直接轉發 [ShizukuManager] 的 flow，不另外存一份：多存一份就多一個會跟真實綁定狀態
     * 走樣的地方，而它表達的是同一件事。
     */
    val service: StateFlow<IMoonClickerService?> = shizukuManager.serviceFlow

    fun onAction(action: FullscreenAction, targetDisplayId: Int) {
        when (action) {
            FullscreenAction.StartApp -> openAppList()
            FullscreenAction.CloseDisplay -> destroyDisplay(targetDisplayId, thenFinish = true)
            FullscreenAction.Exit -> _finishEvents.trySend(Unit)
            FullscreenAction.Home -> injectHomeKey(targetDisplayId)
        }
    }

    private fun injectHomeKey(displayId: Int) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                val downTime = SystemClock.uptimeMillis()
                service.injectKeyEvent(
                    KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0),
                    displayId,
                )
                service.injectKeyEvent(
                    KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0),
                    displayId,
                )
            }.onFailure {
                Timber.e(it)
            }
        }
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

    private fun openAppList() {
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

    private fun destroyDisplay(displayId: Int, thenFinish: Boolean) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.destroyVirtualDisplay(displayId)
            }.onSuccess {
                thumbnailCache.remove(displayId)
                if (thenFinish) _finishEvents.trySend(Unit)
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
