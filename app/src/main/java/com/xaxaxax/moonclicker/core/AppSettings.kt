package com.xaxaxax.moonclicker.core

import android.content.Context
import androidx.core.content.edit
import com.xaxaxax.moonclicker.TopLevelDestination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** 使用者偏好。都得是可觀察的，設定頁與執行路徑要看到同一個值。 */
@Singleton
class AppSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private val _appLanguage = MutableStateFlow(prefs.getString(KEY_APP_LANGUAGE, "") ?: "")

    /**
     * Android 13 以下的語言偏好；空字串代表跟隨系統。Android 13+ 直接問系統的 LocaleManager，
     * 不經過這裡（見 [com.xaxaxax.moonclicker.ui.setting.SettingsViewModel]）。
     */
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    fun setAppLanguage(tag: String) {
        prefs.edit { putString(KEY_APP_LANGUAGE, tag) }
        _appLanguage.value = tag
    }

    private val _developerOptionsUnlocked =
        MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER_OPTIONS_UNLOCKED, false))

    /** 解鎖後才會在「關於」頁面顯示開發人員選項的入口；比照 Android 系統的連點版本號解鎖。 */
    val developerOptionsUnlocked: StateFlow<Boolean> = _developerOptionsUnlocked.asStateFlow()

    fun setDeveloperOptionsUnlocked(unlocked: Boolean) {
        prefs.edit { putBoolean(KEY_DEVELOPER_OPTIONS_UNLOCKED, unlocked) }
        _developerOptionsUnlocked.value = unlocked
    }

    private val _showExternalDisplays =
        MutableStateFlow(prefs.getBoolean(KEY_SHOW_EXTERNAL_DISPLAYS, false))

    /**
     * 開發人員選項：列出並允許鏡像外部 app 建立的虛擬螢幕。Android Studio 等工具會自動建立
     * 虛擬螢幕，一般使用者不需要看到。這是開關本身的值；實際是否生效看 [externalDisplaysVisible]。
     */
    val showExternalDisplays: StateFlow<Boolean> = _showExternalDisplays.asStateFlow()

    fun setShowExternalDisplays(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_EXTERNAL_DISPLAYS, enabled) }
        _showExternalDisplays.value = enabled
    }

    /** 開發人員選項整體關閉時，底下的開關一律不生效。 */
    val externalDisplaysVisible: Flow<Boolean> =
        combine(_developerOptionsUnlocked, _showExternalDisplays) { unlocked, show -> unlocked && show }

    val isExternalDisplaysVisible: Boolean
        get() = _developerOptionsUnlocked.value && _showExternalDisplays.value

    private val _pinnedApps =
        MutableStateFlow(prefs.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet())

    /** 長按釘選的 app package name 集合，fullscreen 的 app 選擇清單用來把常用的排前面。 */
    val pinnedApps: StateFlow<Set<String>> = _pinnedApps.asStateFlow()

    fun setPinned(packageName: String, pinned: Boolean) {
        val next = if (pinned) _pinnedApps.value + packageName else _pinnedApps.value - packageName
        prefs.edit { putStringSet(KEY_PINNED_APPS, next) }
        _pinnedApps.value = next
    }

    companion object {
        private const val PREFS_NAME = "moonclicker_settings"
        private const val KEY_AUTO_FULLSCREEN = "auto_open_fullscreen"
        private const val KEY_AUTO_START_USER_SERVICE = "auto_start_user_service"
        private const val KEY_WORKBENCH_ENABLED = "workbench_enabled"
        private const val KEY_DEFAULT_START_PAGE = "default_start_page"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_DEVELOPER_OPTIONS_UNLOCKED = "developer_options_unlocked"
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_SHOW_EXTERNAL_DISPLAYS = "show_external_displays"

        /**
         * [Activity.attachBaseContext] 跑在 Hilt 欄位注入完成之前，讀不到 [AppSettings] 實例，
         * 只能直接開同一份 SharedPreferences 讀。
         */
        fun readAppLanguageTag(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, "") ?: ""
        }
    }
}
