package com.xaxaxax.moonclicker.ui.setting

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.TopLevelDestination
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.permission.PermissionManager
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.shizuku.UserServiceLifecycle
import com.xaxaxax.moonclicker.workbench.WorkbenchAuthStore
import com.xaxaxax.moonclicker.workbench.WorkbenchServer
import com.xaxaxax.moonclicker.workbench.WorkbenchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

data class SettingsUiState(
    val shizukuStatus: ShizukuConnectionStatus = ShizukuConnectionStatus.NOT_AVAILABLE,
    val hasNotificationPermission: Boolean = false,
    val osAllowSecondaryDisplays: Boolean = false,
    val hasLocalNetworkPermission: Boolean = false,
    val isRefreshing: Boolean = false,
    val autoOpenFullscreen: Boolean = false,
    val defaultStartPage: TopLevelDestination = TopLevelDestination.SCRIPTS,
    /** 空字串代表跟隨系統語言。 */
    val appLanguage: String = "",
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
    /** Shizuku 遠端服務跑在非 uid=2000（adb shell）身分，多半代表使用者用 root 啟動了它。 */
    val isShizukuUidWarning: Boolean = false,
    val isDeveloperOptionsUnlocked: Boolean = false,
    val showExternalDisplays: Boolean = false,
) {
    val canStartUserService: Boolean
        get() = shizukuStatus == ShizukuConnectionStatus.DISCONNECTED

    /** 停止與重啟都會銷毀所有虛擬顯示，執行中的腳本更是直接陪葬。 */
    val canStopUserService: Boolean
        get() = shizukuStatus == ShizukuConnectionStatus.CONNECTED && !isScriptRunning
}

private const val REFRESH_DELAY = 500

/** adb shell 的 uid，Shizuku 官方建議的執行身分；跑在別的 uid（多半是 0=root）值得提醒使用者。 */
private const val SHIZUKU_EXPECTED_UID = 2000

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
    private val isRefreshing = MutableStateFlow(false)

    /**
     * "" 代表跟隨系統語言。Android 13+ 直接讀寫系統的 LocaleManager；13 以下沒有這套機制，
     * 存進 [AppSettings]，靠 Activity 的 attachBaseContext 在下次啟動時套用（見
     * [com.xaxaxax.moonclicker.core.AppLocale]），選完當下不會立即生效。
     */
    private val _appLanguage = MutableStateFlow(currentAppLanguageTag())

    /** 一次性提示（例如透過 Shizuku 取得權限失敗），畫面消費後呼叫 [consumeMessage] 清空。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 先併成一份，是為了讓外層 combine 停在小於等於 5 個具名參數上。 */
    private val userServiceState = combine(
        userServiceLifecycle.snapshot,
        scriptSession.state,
    ) { snapshot, session -> Triple(snapshot.connection, snapshot.autoStartEnabled, session.isRunning) }

    private val permissionCombinedState = combine(
        permissionManager.hasNotificationPermission,
        permissionManager.osAllowSecondaryDisplays,
        permissionManager.hasLocalNetworkPermission,
    ) { notification, secondary, localNetwork ->
        PermissionsStateHolder(notification, secondary, localNetwork)
    }

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

    private val generalSettingsState = combine(
        appSettings.autoOpenFullscreen,
        appSettings.defaultStartPage,
        _appLanguage,
        appSettings.developerOptionsUnlocked,
        appSettings.showExternalDisplays,
    ) { autoOpenFullscreen, defaultStartPage, appLanguage, developerOptionsUnlocked, showExternalDisplays ->
        GeneralSettingsStateHolder(autoOpenFullscreen, defaultStartPage, appLanguage, developerOptionsUnlocked, showExternalDisplays)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        userServiceState,
        permissionCombinedState,
        isRefreshing,
        generalSettingsState,
        workbenchCombinedState,
    ) { (status, autoStart, scriptRunning), permissions, refreshing, general, (workbenchEnabled, workbenchAddress, auth) ->
        SettingsUiState(
            shizukuStatus = status,
            hasNotificationPermission = permissions.hasNotification,
            osAllowSecondaryDisplays = permissions.allowSecondaryDisplays,
            hasLocalNetworkPermission = permissions.hasLocalNetwork,
            isRefreshing = refreshing,
            autoOpenFullscreen = general.autoOpenFullscreen,
            defaultStartPage = general.defaultStartPage,
            appLanguage = general.appLanguage,
            isDeveloperOptionsUnlocked = general.developerOptionsUnlocked,
            showExternalDisplays = general.showExternalDisplays,
            autoStartUserService = autoStart,
            isScriptRunning = scriptRunning,
            workbenchEnabled = workbenchEnabled,
            workbenchAddress = workbenchAddress,
            isPairingActive = auth.active,
            pairingPin = auth.pin,
            pairingExpiryMs = auth.expiry,
            bruteForceProtectionEnabled = auth.bruteForce,
            authorizedTokensCount = auth.tokensCount,
            isShizukuUidWarning = status == ShizukuConnectionStatus.CONNECTED &&
                shizukuManager.currentUid()?.let { it != SHIZUKU_EXPECTED_UID } == true,
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
                    // 下拉重整只該重新檢查狀態，不該彈授權對話框；要授權請按按鈕。
                    shizukuManager.refreshAccess()
                }
                permissionManager.refreshPermissions()
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

    fun setDefaultStartPage(destination: TopLevelDestination) = appSettings.setDefaultStartPage(destination)

    /** tag 為空字串代表跟隨系統語言；Android 13 以下要重開 App 才會套用，畫面上已經有提示文字。 */
    fun setAppLanguage(tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        } else {
            appSettings.setAppLanguage(tag)
            _message.value = context.getString(R.string.settings_language_restart_required)
        }
        _appLanguage.value = tag
    }

    private fun currentAppLanguageTag(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
        } else {
            appSettings.appLanguage.value
        }
    }

    fun setAutoStartUserService(enabled: Boolean) = appSettings.setAutoStartUserService(enabled)

    fun setWorkbenchEnabled(enabled: Boolean) {
        val intent = Intent(context, WorkbenchService::class.java)
        if(enabled && permissionManager.hasLocalNetworkPermission.value) {
            Toast.makeText(
                context,
                R.string.settings_workbench_need_network_permission,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
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

    /** 通知權限說明畫面的「透過 Shizuku 取得權限」動作，失敗時提示改走系統設定。 */
    fun grantNotificationPermissionViaShizuku() {
        viewModelScope.launch {
            val granted = shizukuManager.grantRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            if (granted) {
                permissionManager.refreshPermissions()
            } else {
                _message.value = context.getString(R.string.shizuku_grant_permission_failed)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun getNotificationSettingsIntent() = permissionManager.getNotificationSettingsIntent()
    fun getAppSettingsIntent() = permissionManager.getAppSettingsIntent()

    fun startPairingMode() = authStore.startPairingMode()
    fun stopPairingMode() = authStore.stopPairingMode()
    fun setBruteForceProtectionEnabled(enabled: Boolean) = authStore.setBruteForceProtectionEnabled(enabled)
    fun revokeAllTokens() = authStore.revokeAllTokens()

    fun disableDeveloperOptions() = appSettings.setDeveloperOptionsUnlocked(false)
    fun setShowExternalDisplays(enabled: Boolean) = appSettings.setShowExternalDisplays(enabled)
}

private data class PermissionsStateHolder(
    val hasNotification: Boolean,
    val allowSecondaryDisplays: Boolean,
    val hasLocalNetwork: Boolean,
)

private data class GeneralSettingsStateHolder(
    val autoOpenFullscreen: Boolean,
    val defaultStartPage: TopLevelDestination,
    val appLanguage: String,
    val developerOptionsUnlocked: Boolean,
    val showExternalDisplays: Boolean,
)

private data class PairingStateHolder(
    val active: Boolean,
    val pin: String?,
    val expiry: Long,
    val bruteForce: Boolean,
    val tokensCount: Int,
)
