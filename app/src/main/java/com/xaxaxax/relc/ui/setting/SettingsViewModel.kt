package com.xaxaxax.relc.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.shizuku.hasShizukuPermission
import com.xaxaxax.relc.shizuku.isShizukuAvailable
import com.xaxaxax.relc.shizuku.refreshShizukuPermission
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
    val hasShizukuPermission: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val osAllowSecondaryDisplays: Boolean = false,
    val isRefreshing: Boolean = false
)

private const val REFRESH_DELAY = 500

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext
    private val context: Context
) : ViewModel() {
    private val _osAllowSecondaryDisplays = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        hasShizukuPermission,
        isShizukuAvailable,
        _osAllowSecondaryDisplays,
        isRefreshing
    ) { hasShizuku, isShizuku, allowSecondary, isRefreshing ->
        SettingsUiState(
            hasShizukuPermission = hasShizuku,
            isShizukuAvailable = isShizuku,
            osAllowSecondaryDisplays = allowSecondary,
            isRefreshing = isRefreshing
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
                    if (!uiState.value.hasShizukuPermission)
                        refreshShizukuPermission()
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

    fun requestShizukuPermission() = com.xaxaxax.relc.shizuku.requestShizukuPermission()


    fun openShizukuIntent(): Intent {
        val intent =
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(
                    Intent.ACTION_VIEW,
                    "market://details?id=moe.shizuku.privileged.api".toUri()
                ).takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent(
                    Intent.ACTION_VIEW,
                    "https://shizuku.rikka.app/download/".toUri()
                )
        return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

}
