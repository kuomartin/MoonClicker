package com.xaxaxax.relc.ui.displays

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.ShizukuUserService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val isShizukuAvailable: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class DisplaysViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val displayIds = MutableStateFlow<List<Int>>(emptyList())
    private val isLoading = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)
    private val refreshingDelay = 1000.milliseconds
    private var serviceHandle: ShizukuUserService.Handle<IRelcShizukuService>? = null

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
        shizukuManager.isShizukuAvailable,
        shizukuManager.hasPermission,
        isLoading,
        isRefreshing
    ) { ids, available, permission, loading, refreshing ->
        DisplaysUiState(
            displayIds = ids,
            isShizukuAvailable = available,
            hasShizukuPermission = permission,
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
        if (!shizukuManager.isShizukuAvailable.value || !shizukuManager.hasPermission.value) {
            return
        }

        viewModelScope.launch {
            isLoading.value = true
            if (fromPullToRefresh)
                isRefreshing.value = true
            try {
                withService { service ->
                    val ids = service.virtualDisplays.toList()
                    displayIds.value = ids
                }
            } finally {
                isLoading.value = false
                if (fromPullToRefresh) {
                    delay(refreshingDelay)
                    isRefreshing.value = false
                }
            }
        }
    }

    fun createDisplay(config: DisplayConfig = defaultConfig) {
        viewModelScope.launch {
            withService { service ->
                val ctrl = VirtualDisplayController(service)
                ctrl.create(config)
            }
            refreshDisplays()
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────
    private suspend fun withService(block: (IRelcShizukuService) -> Unit) {
        runCatching {
            val handle = serviceHandle ?: run {
                // 使用 lastUpdateTime 作為版本：每次重裝都會改變，
                // 即使 versionCode 沒有更新也會讓 Shizuku 重啟 UserService 進程。
                val installVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0)
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
        }
    }

}
