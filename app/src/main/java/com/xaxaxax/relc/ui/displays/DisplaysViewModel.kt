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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DisplaysUiState(
    val displayIds: List<Int> = emptyList(),
    val isShizukuAvailable: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val isLoading: Boolean = false
)

@HiltViewModel
class DisplaysViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _displayIds = MutableStateFlow<List<Int>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
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
        _displayIds,
        shizukuManager.isShizukuAvailable,
        shizukuManager.hasPermission,
        _isLoading
    ) { ids, available, permission, loading ->
        DisplaysUiState(
            displayIds = ids,
            isShizukuAvailable = available,
            hasShizukuPermission = permission,
            isLoading = loading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DisplaysUiState()
    )

    init {
        refreshDisplays()
    }

    fun refreshDisplays() {
        if (!shizukuManager.isShizukuAvailable.value || !shizukuManager.hasPermission.value) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                withService { service ->
                    val ids = service.virtualDisplays.toList()
                    _displayIds.value = ids
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh displays")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createDisplay(config: DisplayConfig = defaultConfig) {
        viewModelScope.launch {
            withService { service ->
                val ctrl = VirtualDisplayController(service)
                ctrl.create(config)
                refreshDisplays()
            }
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────
    private fun syncState(
        displayIds: List<Int>? = null,
        isLoading: Boolean? = null
    ) {
        displayIds?.let { _displayIds.value = it }
        isLoading?.let { _isLoading.value = it }
    }

    private fun withService(block: (IRelcShizukuService) -> Unit) {
        viewModelScope.launch {
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
            syncState(isLoading = false)
        }
    }

}
