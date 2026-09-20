package com.xaxaxax.relc.ui.about

import android.content.Context
import androidx.lifecycle.ViewModel
import com.xaxaxax.relc.R
import com.xaxaxax.relc.core.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 連點版本號解鎖開發人員選項，比照 Android 系統。點擊次數只存在記憶體裡，離開「關於」頁
 * （這個 ViewModel 被銷毀）就重新算，不會累積成不小心解鎖。
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
) : ViewModel() {
    val isDeveloperOptionsUnlocked: StateFlow<Boolean> = appSettings.developerOptionsUnlocked

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var tapCount = 0

    fun onVersionTapped() {
        if (appSettings.developerOptionsUnlocked.value) {
            _message.value = context.getString(R.string.about_developer_already_unlocked)
            return
        }

        tapCount++
        val remaining = TAPS_REQUIRED - tapCount
        when {
            remaining <= 0 -> {
                appSettings.setDeveloperOptionsUnlocked(true)
                tapCount = 0
                _message.value = context.getString(R.string.about_developer_unlocked)
            }
            remaining in 1..COUNTDOWN_STARTS_AT -> {
                _message.value = context.getString(R.string.about_developer_taps_remaining, remaining)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val TAPS_REQUIRED = 7
        const val COUNTDOWN_STARTS_AT = 3
    }
}
