package com.xaxaxax.relc.ui.setting

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.ShizukuUserService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val hasShizukuPermission: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val osAllowSecondaryDisplays: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager,
    @ApplicationContext
    private val context: Context
) : ViewModel() {
    private val _hasOverlayPermission = MutableStateFlow(false)
    private val _osAllowSecondaryDisplays = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        shizukuManager.hasPermission,
        shizukuManager.isShizukuAvailable,
        _hasOverlayPermission,
        _osAllowSecondaryDisplays
    ) { hasShizuku, isShizuku, hasOverlay, allowSecondary ->
        SettingsUiState(
            hasShizukuPermission = hasShizuku,
            isShizukuAvailable = isShizuku,
            hasOverlayPermission = hasOverlay,
            osAllowSecondaryDisplays = allowSecondary
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            _hasOverlayPermission.value = Settings.canDrawOverlays(context)
            _osAllowSecondaryDisplays.value = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
            )
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun overlayPermissionIntent(): Intent {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        )
        return intent
    }

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


    fun requestOverlayPermissionByShizuku() {
        viewModelScope.launch {
            val installVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
                .toInt()
            val handle = ShizukuUserService.connect<IRelcShizukuService>(
                serviceClass = RelcShizukuService::class,
                asInterface = IRelcShizukuService.Stub::asInterface,
                version = installVersion,
            )
            handle.service.setOverlayAllowed(context.packageName)
            refreshPermissions()
        }
    }

}
