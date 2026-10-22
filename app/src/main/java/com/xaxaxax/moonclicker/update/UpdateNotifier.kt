package com.xaxaxax.moonclicker.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xaxaxax.moonclicker.EXTRA_INSTALL_UPDATE
import com.xaxaxax.moonclicker.EXTRA_NAV_TARGET
import com.xaxaxax.moonclicker.MoonClickerActivity
import com.xaxaxax.moonclicker.NAV_TARGET_SETTINGS
import com.xaxaxax.moonclicker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface ReadyNotifier {
    fun notifyReady(version: Version)
    fun cancelReady()
}

/** 「已下載，點此安裝」通知。沒有通知權限就不發，設定頁的更新區塊一樣會顯示「安裝」。 */
@Singleton
class UpdateNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ReadyNotifier {
    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_label),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    override fun notifyReady(version: Version) {
        val intent = Intent(context, MoonClickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_NAV_TARGET, NAV_TARGET_SETTINGS)
            .putExtra(EXTRA_INSTALL_UPDATE, true)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_ready_notification_title, version.toString()))
            .setContentText(context.getString(R.string.update_ready_notification_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Update notification not posted; permission missing")
        }
    }

    override fun cancelReady() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "update"
        const val NOTIFICATION_ID = 3000
        const val REQUEST_CODE = 3000
    }
}
