package com.xaxaxax.relc.ui.displays

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.createVirtualDisplay
import com.xaxaxax.relc.ui.displaydetail.DisplayThumbnailCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

data class DisplayCardInfo(
    val displayId: Int,
    val name: String = "Display $displayId",
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val isPhysical: Boolean = false,
    val isMirrorActive: Boolean = false,
    val thumbnail: ImageBitmap? = null,
)

data class DisplaysUiState(
    val displays: List<DisplayCardInfo> = emptyList(),
    val shizukuStatus: ShizukuConnectionStatus = ShizukuConnectionStatus.NOT_AVAILABLE,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

private const val REFRESH_DELAY = 500


@HiltViewModel
class DisplaysViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val thumbnailCache: DisplayThumbnailCache,
) : ViewModel() {
    private val displays = MutableStateFlow<List<DisplayCardInfo>>(emptyList())
    private val isLoading = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    val defaultConfig by lazy {
        val width = Resources.getSystem().displayMetrics.widthPixels
        val height = Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi
        DisplayConfig(
            name = "ReLC",
            width = width,
            height = height,
            densityDpi = density,
        )
    }

    val uiState: StateFlow<DisplaysUiState> = combine(
        displays,
        shizukuManager.statusFlow,
        isLoading,
        isRefreshing
    ) { displays, shizukuStatus, loading, refreshing ->
        DisplaysUiState(
            displays = displays,
            shizukuStatus = shizukuStatus,
            isLoading = loading,
            isRefreshing = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DisplaysUiState()
    )

    init {
        // 連上就自動載入一次。自動連線讓「進畫面時服務還沒接上」變成常態，
        // 若只在 init 載入一次，使用者會看到一份永遠是空的清單。
        viewModelScope.launch {
            shizukuManager.statusFlow
                .map { it.isConnected }
                .distinctUntilChanged()
                .collect { connected -> if (connected) refreshDisplays() }
        }
    }

    fun onShizukuAction() = shizukuManager.requestPermissionOrConnect()

    fun refreshDisplays(fromPullToRefresh: Boolean = false) {
        if (!shizukuManager.status.isConnected && !fromPullToRefresh) {
            return
        }
        isLoading.value = true
        viewModelScope.launch {
            try {
                if (fromPullToRefresh) {
                    isRefreshing.value = true
                    if (!shizukuManager.status.isConnected) {
                        shizukuManager.requestPermissionOrConnect()
                    }
                }
                shizukuManager.withService { service ->
                    val displayInfos = runCatching { service.displayInfos.toList() }.getOrElse { emptyList() }
                    val ids = displayInfos.map { it.displayId }
                    computeRemovedDisplayIds(displays.value.map { it.displayId }, ids)
                        .forEach { thumbnailCache.remove(it) }
                    displays.value = withContext(Dispatchers.Default) {
                        displayInfos.map { info ->
                            DisplayCardInfo(
                                displayId = info.displayId,
                                name = info.name ?: "Display ${info.displayId}",
                                width = info.width,
                                height = info.height,
                                densityDpi = info.densityDpi,
                                isPhysical = info.isPhysical,
                                isMirrorActive = info.isMirrorActive,
                                thumbnail = if (info.isPhysical && !info.isMirrorActive) null else thumbnailCache.get(info.displayId),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "refreshDisplays failed")
            } finally {
                isLoading.value = false
                if (fromPullToRefresh) {
                    delay(REFRESH_DELAY.milliseconds)
                    isRefreshing.value = false
                }
            }
        }
    }

    fun toggleMirror(displayId: Int, enable: Boolean) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                if (enable) {
                    service.acquireDisplayMirror(displayId)
                } else {
                    service.releaseDisplayMirror(displayId)
                }
            }
            refreshDisplays()
        }
    }

    fun createDisplay(config: DisplayConfig = defaultConfig) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.createVirtualDisplay(config)
            }
            refreshDisplays()
        }
    }

    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            shizukuManager.withService { service -> service.destroyVirtualDisplay(displayId) }
            thumbnailCache.remove(displayId)
            refreshDisplays()
        }
    }
}

/** 上一輪清單裡有、這一輪沒了的 displayId——縮圖快取該一併清掉的對象。 */
internal fun computeRemovedDisplayIds(previousIds: List<Int>, currentIds: List<Int>): Set<Int> =
    previousIds.toSet() - currentIds.toSet()
