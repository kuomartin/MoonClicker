package com.xaxaxax.relc.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Shizuku 連線與權限管理類別。
 * * 負責檢查 Shizuku 服務的可用性、管理權限請求與狀態。
 * 採用 Coroutine Flow 來更有效地管理狀態。
 */
class ShizukuManager {

    private val _isShizukuAvailable = MutableStateFlow(Shizuku.pingBinder())
    val isShizukuAvailable = _isShizukuAvailable.asStateFlow()

    private val _hasPermission = MutableStateFlow(checkCurrentPermission())
    val hasPermission = _hasPermission.asStateFlow()

    // 監聽器物件化，方便註冊與註銷
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateStatus()
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _hasPermission.value = (grantResult == PackageManager.PERMISSION_GRANTED)
    }

    private fun checkCurrentPermission(): Boolean {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus() {
        val available = Shizuku.pingBinder()
        _isShizukuAvailable.value = available
        _hasPermission.value = if (available) {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        Timber.d("Shizuku Status Updated: Available=$available, Permission=${_hasPermission.value}")
    }

    fun startListening() {
        // 1. 註冊 Binder 接收監聽 (當 Shizuku 啟動或 App 重新連上時)
        Shizuku.addBinderReceivedListener(binderReceivedListener)

        // 2. 註冊 Binder 死亡監聽 (當 Shizuku 服務被停止時)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 3. 註冊權限結果監聽
        Shizuku.addRequestPermissionResultListener(permissionListener)

        // 初始狀態更新
        updateStatus()
    }

    fun stopListening() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    fun requestPermission(code: Int = 100) {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(code)
        } else {
            Timber.e("Shizuku binder not available")
        }
    }
}