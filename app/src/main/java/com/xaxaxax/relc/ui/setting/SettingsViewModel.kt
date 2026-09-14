package com.xaxaxax.relc.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.permission.PermissionManager
import com.xaxaxax.relc.script.ScriptSession
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
    val autoStartUserService: Boolean = true,
    /** 腳本跑在 UserService 上，停掉服務會把它一起帶走。 */
    val isScriptRunning: Boolean = false,
) {
    val canStartUserService: Boolean
        get() = shizukuStatus == ShizukuConnectionStatus.DISCONNECTED

    /** 停止與重啟都會銷毀所有虛擬顯示，執行中的腳本更是直接陪葬。 */
    val canStopUserService: Boolean
        get() = shizukuStatus == ShizukuConnectionStatus.CONNECTED && !isScriptRunning
}

private const val REFRESH_DELAY = 500

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val shizukuManager: ShizukuManager,
    private val appSettings: AppSettings,
    private val scriptSession: ScriptSession,
) : ViewModel() {
    private val _osAllowSecondaryDisplays = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    /** 先併成一份，是為了讓外層 combine 停在四個具名參數上，不必退化成靠索引轉型的 vararg 版。 */
    private val userServiceState = combine(
        shizukuManager.statusFlow,
        appSettings.autoStartUserService,
        scriptSession.state,
    ) { status, autoStart, session -> Triple(status, autoStart, session.isRunning) }

    val uiState: StateFlow<SettingsUiState> = combine(
        userServiceState,
        permissionManager.osAllowSecondaryDisplaysFlow,
        isRefreshing,
        appSettings.autoOpenFullscreen,
    ) { (status, autoStart, scriptRunning), allowSecondary, refreshing, autoOpenFullscreen ->
        SettingsUiState(
            shizukuStatus = status,
            osAllowSecondaryDisplays = allowSecondary,
            isRefreshing = refreshing,
            autoOpenFullscreen = autoOpenFullscreen,
            autoStartUserService = autoStart,
            isScriptRunning = scriptRunning,
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

    fun setAutoStartUserService(enabled: Boolean) = appSettings.setAutoStartUserService(enabled)

    fun startUserService() = shizukuManager.startUserService()

    fun stopUserService() = shizukuManager.stopUserService()

    fun restartUserService() = shizukuManager.restartUserService()

    fun getOpenShizukuIntent() = shizukuManager.getOpenShizukuIntent()
    fun requestShizukuPermission() = shizukuManager.requestPermission()
}
