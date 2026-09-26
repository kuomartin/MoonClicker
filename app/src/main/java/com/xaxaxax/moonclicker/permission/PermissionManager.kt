package com.xaxaxax.moonclicker.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** compileSdk 的 android.jar 還沒有這個常數（targetSdk 37 才新增），先用字面值宣告。 */
private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Singleton
class PermissionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _hasNotificationPermission = MutableStateFlow(getHasNotificationPermission())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private val _osAllowSecondaryDisplays = MutableStateFlow(getOsAllowSecondaryDisplays())
    val osAllowSecondaryDisplays: StateFlow<Boolean> = _osAllowSecondaryDisplays.asStateFlow()

    private val _hasLocalNetworkPermission = MutableStateFlow(getHasLocalNetworkPermission())
    val hasLocalNetworkPermission: StateFlow<Boolean> = _hasLocalNetworkPermission.asStateFlow()

    fun refreshPermissions() {
        _hasNotificationPermission.value = getHasNotificationPermission()
        _osAllowSecondaryDisplays.value = getOsAllowSecondaryDisplays()
        _hasLocalNetworkPermission.value = getHasLocalNetworkPermission()
    }

    fun getHasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun getHasLocalNetworkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.BAKLAVA) {
            true
        }
        else {
            context.checkSelfPermission(
                PERMISSION_ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getOsAllowSecondaryDisplays(): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
        )
    }

    fun getNotificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            getAppSettingsIntent()
        }
    }

    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}