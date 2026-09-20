package com.xaxaxax.relc.core

import android.content.Context
import androidx.core.content.edit
import com.xaxaxax.relc.TopLevelDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 使用者偏好。都得是可觀察的，設定頁與執行路徑要看到同一個值。 */
@Singleton
class AppSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("relc_settings", Context.MODE_PRIVATE)

    private val _autoOpenFullscreen =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_FULLSCREEN, false))

    /**
     * 腳本跑在虛擬顯示上時，要不要自動打開鏡像畫面。
     *
     * 做成選項而不是寫死的行為：headless 執行才是常態，但調腳本的時候會想看到畫面。
     */
    val autoOpenFullscreen: StateFlow<Boolean> = _autoOpenFullscreen.asStateFlow()

    fun setAutoOpenFullscreen(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_FULLSCREEN, enabled) }
        _autoOpenFullscreen.value = enabled
    }

    private val _autoStartUserService =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_START_USER_SERVICE, true))

    /**
     * 沒有執行中的 UserService 時，要不要自動把它啟動起來。
     *
     * 關閉時仍會自動接上已在執行的服務（Shizuku 的 user service 是 daemon，會活過 App 被殺），
     * 只是不再主動建立新的——想完全掌握特權行程何時存在的人要的是這個。
     */
    val autoStartUserService: StateFlow<Boolean> = _autoStartUserService.asStateFlow()

    fun setAutoStartUserService(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_START_USER_SERVICE, enabled) }
        _autoStartUserService.value = enabled
    }

    private val _defaultStartPage = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_START_PAGE, null)
            ?.let { name -> runCatching { TopLevelDestination.valueOf(name) }.getOrNull() }
            ?: TopLevelDestination.SCRIPTS
    )

    /** App 啟動時第一個顯示的頂層頁面，目前固定為 Scripts；這裡讓使用者可以自己選。 */
    val defaultStartPage: StateFlow<TopLevelDestination> = _defaultStartPage.asStateFlow()

    fun setDefaultStartPage(destination: TopLevelDestination) {
        prefs.edit { putString(KEY_DEFAULT_START_PAGE, destination.name) }
        _defaultStartPage.value = destination
    }

    private val _workbenchEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_WORKBENCH_ENABLED, false))

    /** Script Workbench 內嵌 server 的開關，與腳本執行狀態、畫面前後景無關。 */
    val workbenchEnabled: StateFlow<Boolean> = _workbenchEnabled.asStateFlow()

    fun setWorkbenchEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WORKBENCH_ENABLED, enabled) }
        _workbenchEnabled.value = enabled
    }

    private companion object {
        const val KEY_AUTO_FULLSCREEN = "auto_open_fullscreen"
        const val KEY_AUTO_START_USER_SERVICE = "auto_start_user_service"
        const val KEY_WORKBENCH_ENABLED = "workbench_enabled"
        const val KEY_DEFAULT_START_PAGE = "default_start_page"
    }
}
