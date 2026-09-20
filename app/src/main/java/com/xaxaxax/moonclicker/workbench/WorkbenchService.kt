package com.xaxaxax.moonclicker.workbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.MoonClickerActivity
import com.xaxaxax.moonclicker.core.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Script Workbench server 的容器。開關由 Settings 頁手動控制，與 [com.xaxaxax.moonclicker.script.ScriptSession]
 * 的執行狀態、畫面前後景都無關——這是它獨立於 `ScriptStatusNotifier` 用自己的
 * notification channel 的原因。
 */
@AndroidEntryPoint
class WorkbenchService : Service() {
    @Inject
    lateinit var workbenchServer: WorkbenchServer

    @Inject
    lateinit var appSettings: AppSettings

    private val connectivityManager by lazy { getSystemService<ConnectivityManager>() }
    private val nsdManager by lazy { getSystemService<NsdManager>() }
    private var registrationListener: NsdManager.RegistrationListener? = null

    // QR code（見 #56）顯示的位址要在切換網路（例如 WiFi 換一個）時跟著更新，不只是
    // server 剛啟動那一刻的快照。
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            workbenchServer.refreshAddress()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appSettings.setWorkbenchEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()

        // bind 失敗（例如 port 被佔用）就乾脆別把這個 process 帶進前景服務——
        // startForeground() 之前拋例外會讓整個 App crash，不只是這個功能失敗。
        if (!workbenchServer.start()) {
            stopSelf()
            return START_NOT_STICKY
        }

        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        registerMdnsService()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.workbench_notification_title))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.workbench_notification_action_stop),
                stopIntent(),
            )
            .build()

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
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        unregisterMdnsService()
        workbenchServer.stop()
        super.onDestroy()
    }

    private fun registerMdnsService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "MoonClicker (${Build.MODEL})"
            serviceType = "_moonclicker-workbench._tcp"
            port = WorkbenchServer.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterMdnsService() {
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
            registrationListener = null
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MoonClickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, WorkbenchService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        private const val ACTION_STOP = "com.xaxaxax.moonclicker.action.STOP_WORKBENCH"
    }
}
