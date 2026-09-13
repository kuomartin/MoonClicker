package com.xaxaxax.relc.core

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 使用者偏好。目前只有一項，但它得是可觀察的，設定頁與執行路徑都要看到同一個值。 */
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

    private companion object {
        const val KEY_AUTO_FULLSCREEN = "auto_open_fullscreen"
    }
}
