package com.xaxaxax.relc.ui.displays

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.createVirtualDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

data class DisplaysUiState(
    val displayIds: List<Int> = emptyList(),
    val isShizukuReady: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

private const val REFRESH_DELAY = 500


@HiltViewModel
class DisplaysViewModel @Inject constructor(
//    @ApplicationContext private val context: Context
    private val shizukuManager: ShizukuManager
) : ViewModel() {
    private val displayIds = MutableStateFlow<List<Int>>(emptyList())
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
        displayIds,
        shizukuManager.isReadyFlow,
        isLoading,
        isRefreshing
    ) { ids, ready, loading, refreshing ->
        DisplaysUiState(
            displayIds = ids,
            isShizukuReady = ready,
            isLoading = loading,
            isRefreshing = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DisplaysUiState()
    )

    init {
        refreshDisplays()
    }

    fun refreshDisplays(fromPullToRefresh: Boolean = false) {
        if (!uiState.value.isShizukuReady && !fromPullToRefresh) {
            return
        }
        isLoading.value = true
        viewModelScope.launch {
            try {
                if (fromPullToRefresh) {
                    isRefreshing.value = true
                    if (!uiState.value.isShizukuReady) {
                        shizukuManager.requestPermission()
                    }
                }
                shizukuManager.withService { service ->
                    val ids = service.virtualDisplays.toList()
                    displayIds.value = ids
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

    fun createDisplay(config: DisplayConfig = defaultConfig) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.createVirtualDisplay(config)
            }
            refreshDisplays()
        }
    }
}
