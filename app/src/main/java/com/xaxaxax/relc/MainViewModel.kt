package com.xaxaxax.relc

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.ShizukuUserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _hasNotificationPermission = MutableStateFlow(false)
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission

    // 懸浮視窗權限狀態
    private val _hasOverlayPermission = MutableStateFlow(false)
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission

    // Shizuku狀態 - 直接觀察ShizukuManager的LiveData
    val shizukuManager: ShizukuManager = ShizukuManager().apply {
        startListening()
    }
    val shizukuAvailable: StateFlow<Boolean> = shizukuManager.isShizukuAvailable
    val hasShizukuPermission: StateFlow<Boolean> = shizukuManager.hasPermission


    /**
     * 檢查通知權限
     */
    private fun checkNotificationPermission() {
        val context: Context = getApplication()
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 以下沒有 Runtime Permission
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        _hasNotificationPermission.value = hasPermission
    }

    /**
     * 檢查懸浮視窗權限
     */
    private fun checkOverlayPermission() {
        val context: Context = getApplication()
        _hasOverlayPermission.value = Settings.canDrawOverlays(context)
    }

    /**
     * 處理通知權限結果
     */
    fun onNotificationPermissionResult(isGranted: Boolean) {
        // Use in A13 or later
        _hasNotificationPermission.value = isGranted
    }

    fun refreshPermissionStatus() {
        checkOverlayPermission()
        checkNotificationPermission()
    }

    fun notificationSettingsIntent(): Intent {
        val context: Context = getApplication()
        return Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    fun overlaySettingsIntent(): Intent {
        val context: Context = getApplication()
        return Intent().apply {
            action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            data = "package:${context.packageName}".toUri()
//            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }


    /**
     * Open Shizuku or open Play Store to install Shizuku
     */
    fun openShizukuIntent(): Intent {
        val context: Context = getApplication()
        val intent =
            // 嘗試開啟 Shizuku app
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?:
                // Play Store 的 Shizuku 頁面
                Intent(
                    Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api".toUri()
                ).takeIf { it.resolveActivity(context.packageManager) != null }
                ?:
                // 如果沒有 Play Store，使用瀏覽器
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api".toUri()
                )
        Timber.d("Intent = $intent")
        return intent
    }

    /**
     * 請求Shizuku權限
     */
    fun requestShizukuPermission() {
        viewModelScope.launch {
            try {
                shizukuManager.requestPermission()
            } catch (e: Exception) {
                Timber.e(e, "請求 Shizuku 權限失敗")
            }
        }
    }

    fun grantPermissionByShizuku() {
        if (!shizukuAvailable.value or !hasShizukuPermission.value) return
        viewModelScope.launch {
            val context: Context = getApplication()
            val handle = ShizukuUserService.connect<IRelcShizukuService>(
                serviceClass = RelcShizukuService::class,
                asInterface = IRelcShizukuService.Stub::asInterface
            )
            handle.service.setOverlayAllowed(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handle.service.grantRuntimePermission(
                    context.packageName, Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                Timber.w("Lower then Android 13, skip for android.permission.POST_NOTIFICATIONS.")
            }

            handle.unbind()
            refreshPermissionStatus()
        }
    }
}