package com.xaxaxax.relc.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.permission.PermissionManager
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

data class SettingsUiState(
    val shizukuStatus: ShizukuConnectionStatus = ShizukuConnectionStatus.NOT_AVAILABLE,
    val osAllowSecondaryDisplays: Boolean = false,
    val isRefreshing: Boolean = false,
    val autoOpenFullscreen: Boolean = false,
)

private const val REFRESH_DELAY = 500

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val shizukuManager: ShizukuManager,
    private val appSettings: AppSettings,
) : ViewModel() {
    private val _osAllowSecondaryDisplays = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        shizukuManager.statusFlow,
        permissionManager.osAllowSecondaryDisplaysFlow,
        isRefreshing,
        appSettings.autoOpenFullscreen,
    ) { shizukuStatus, allowSecondary, isRefreshing, autoOpenFullscreen ->
        SettingsUiState(
            shizukuStatus = shizukuStatus,
            osAllowSecondaryDisplays = allowSecondary,
            isRefreshing = isRefreshing,
            autoOpenFullscreen = autoOpenFullscreen,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        refreshPermissions()
    }

    fun refreshPermissions(fromPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                if (fromPullToRefresh) {
                    Timber.d("fromPullToRefresh : $uiState")
                    isRefreshing.value = true
                    // 下拉重整只該重新檢查狀態，不該彈授權對話框；要授權請按健康檢查卡片上的按鈕。
                    shizukuManager.refreshAccess()
                }
                _osAllowSecondaryDisplays.value = context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
                )
            } catch (t: Throwable) {
                Timber.e(t, "refreshPermissions failed")
            } finally {
                if (fromPullToRefresh) {
                    delay(REFRESH_DELAY.milliseconds)
                    Timber.d("fromPullToRefresh Done")
                    isRefreshing.value = false
                }
            }
        }
    }

    fun setAutoOpenFullscreen(enabled: Boolean) = appSettings.setAutoOpenFullscreen(enabled)

    fun getOpenShizukuIntent() = shizukuManager.getOpenShizukuIntent()
    fun requestShizukuPermission() = shizukuManager.requestPermission()
}
