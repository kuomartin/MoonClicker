package com.xaxaxax.relc.ui.displays

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.createVirtualDisplay
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
    val width: Int,
    val height: Int,
    val densityDpi: Int,
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
    private val shizukuManager: ShizukuManager
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
                    val ids = service.virtualDisplays.toList()
                    displays.value = withContext(Dispatchers.Default) {
                        ids.mapNotNull { id -> readDisplayCardInfo(id) }
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

    private fun readDisplayCardInfo(displayId: Int): DisplayCardInfo? {
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = displayManager.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplayCardInfo(
            displayId = displayId,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
        )
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
            refreshDisplays()
        }
    }
}
