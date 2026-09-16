package com.xaxaxax.relc.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.permission.PermissionManager
import com.xaxaxax.relc.script.ScriptSession
import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.UserServiceLifecycle
import com.xaxaxax.relc.workbench.WorkbenchAuthStore
import com.xaxaxax.relc.workbench.WorkbenchServer
import com.xaxaxax.relc.workbench.WorkbenchService
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
    val workbenchEnabled: Boolean = false,
    /** Server 目前監聽的 "ip:port"，供 QR code 配對顯示；未啟動或還沒 bind 完成時是 null。 */
    val workbenchAddress: String? = null,
    /** 腳本跑在 UserService 上，停掉服務會把它一起帶走。 */
    val isScriptRunning: Boolean = false,
    val isPairingActive: Boolean = false,
    val pairingPin: String? = null,
    val pairingExpiryMs: Long = 0L,
    val bruteForceProtectionEnabled: Boolean = true,
    val authorizedTokensCount: Int = 0,
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
    private val userServiceLifecycle: UserServiceLifecycle,
    private val scriptSession: ScriptSession,
    private val workbenchServer: WorkbenchServer,
    val authStore: WorkbenchAuthStore,
) : ViewModel() {
    private val _osAllowSecondaryDisplays = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    /** 先併成一份，是為了讓外層 combine 停在小於等於 5 個具名參數上。 */
    private val userServiceState = combine(
        userServiceLifecycle.snapshot,
        scriptSession.state,
    ) { snapshot, session -> Triple(snapshot.connection, snapshot.autoStartEnabled, session.isRunning) }

    private val authState = combine(
        authStore.isPairingActive,
        authStore.pairingPin,
        authStore.pairingExpiryMs,
        authStore.bruteForceProtectionEnabled,
        authStore.authorizedTokens,
    ) { active, pin, expiry, bruteForce, tokens ->
        PairingStateHolder(active, pin, expiry, bruteForce, tokens.size)
    }

    private val workbenchCombinedState = combine(
        appSettings.workbenchEnabled,
        workbenchServer.address,
        authState,
    ) { enabled, address, auth -> Triple(enabled, address, auth) }

    val uiState: StateFlow<SettingsUiState> = combine(
        userServiceState,
        permissionManager.osAllowSecondaryDisplaysFlow,
        isRefreshing,
        appSettings.autoOpenFullscreen,
        workbenchCombinedState,
    ) { (status, autoStart, scriptRunning), allowSecondary, refreshing, autoOpenFullscreen, (workbenchEnabled, workbenchAddress, auth) ->
        SettingsUiState(
            shizukuStatus = status,
            osAllowSecondaryDisplays = allowSecondary,
            isRefreshing = refreshing,
            autoOpenFullscreen = autoOpenFullscreen,
            autoStartUserService = autoStart,
            isScriptRunning = scriptRunning,
            workbenchEnabled = workbenchEnabled,
            workbenchAddress = workbenchAddress,
            isPairingActive = auth.active,
            pairingPin = auth.pin,
            pairingExpiryMs = auth.expiry,
            bruteForceProtectionEnabled = auth.bruteForce,
            authorizedTokensCount = auth.tokensCount,
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

    fun setWorkbenchEnabled(enabled: Boolean) {
        val intent = Intent(context, WorkbenchService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
        appSettings.setWorkbenchEnabled(enabled)
    }

    fun startUserService() = shizukuManager.startUserService()

    fun stopUserService() = shizukuManager.stopUserService()

    fun restartUserService() = shizukuManager.restartUserService()

    fun getOpenShizukuIntent() = shizukuManager.getOpenShizukuIntent()
    fun requestShizukuPermission() = shizukuManager.requestPermission()

    fun startPairingMode() = authStore.startPairingMode()
    fun stopPairingMode() = authStore.stopPairingMode()
    fun setBruteForceProtectionEnabled(enabled: Boolean) = authStore.setBruteForceProtectionEnabled(enabled)
    fun revokeAllTokens() = authStore.revokeAllTokens()
}

private data class PairingStateHolder(
    val active: Boolean,
    val pin: String?,
    val expiry: Long,
    val bruteForce: Boolean,
    val tokensCount: Int,
)
