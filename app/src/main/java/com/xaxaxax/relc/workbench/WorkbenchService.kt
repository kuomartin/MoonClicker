package com.xaxaxax.relc.workbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xaxaxax.relc.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Script Workbench server 的容器。開關由 Settings 頁手動控制，與 [com.xaxaxax.relc.script.ScriptSession]
 * 的執行狀態、畫面前後景都無關——這是它獨立於 `ScriptStatusNotifier` 用自己的
 * notification channel 的原因。
 */
@AndroidEntryPoint
class WorkbenchService : Service() {
    @Inject
    lateinit var workbenchServer: WorkbenchServer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        // bind 失敗（例如 port 被佔用）就乾脆別把這個 process 帶進前景服務——
        // startForeground() 之前拋例外會讓整個 App crash，不只是這個功能失敗。
        if (!workbenchServer.start()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.workbench_notification_title))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 是 API 34 才有的型別——用它不需要 connectedDevice
        // 要求的藍牙/USB/NFC 類權限，這個 service 只是個 WiFi HTTP server，掛 connectedDevice
        // 在真實裝置上會被系統以 SecurityException 直接拒絕啟動（見這次的 bug 回報）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        workbenchServer.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.workbench_notification_channel_label),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.workbench_notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "workbench_status"
        private const val NOTIFICATION_ID = 3001
    }
}
