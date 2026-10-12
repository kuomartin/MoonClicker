package com.xaxaxax.moonclicker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xaxaxax.moonclicker.EXTRA_NAV_TARGET
import com.xaxaxax.moonclicker.NAV_TARGET_SCRIPTS
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.MoonClickerActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 執行中的腳本唯一的系統層可見處（Overlay UI 已移除，見 ADR-0007）。
 *
 * 每支執行中的腳本一則常駐通知，動作只有「停止」；全部放在同一個 group，由一則 summary 收合。
 *
 * 通知**不會**讓行程活著。被移除的 AccessibilityService 原本順帶有這個效果，而
 * `setOngoing(true)` 沒有，所以長時間執行的腳本可能在使用者切到別的 app 之後被系統殺掉。
 * 要真正錨住執行，需要的是前景服務，那是另一個還沒做的決定。
 */
@Singleton
class ScriptStatusNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var messageId = MESSAGE_NOTIFICATION_ID_BASE

    /** 腳本 id → 常駐通知 id。同一支腳本每次執行沿用同一個 id。 */
    private val statusIds = HashMap<String, Int>()
    private val shownStatus = HashSet<String>()

    init {
        createChannels()
    }

    @Synchronized
    fun showRunning(scriptId: String, scriptName: String, displayId: Int?) {
        val id = statusIds.getOrPut(scriptId) { STATUS_NOTIFICATION_ID_BASE + statusIds.size }
        val target = when (displayId) {
            null -> ""
            0 -> context.getString(R.string.script_target_physical)
            else -> context.getString(R.string.script_target_virtual, displayId)
        }

        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.script_status_title, scriptName))
            .setContentText(target)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openScriptsIntent())
            .setGroup(STATUS_GROUP)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.script_status_action_stop),
                stopIntent(scriptId, requestCode = id),
            )
            .build()

        shownStatus += scriptId
        notify(SUMMARY_NOTIFICATION_ID, summary())
        notify(id, notification)
    }

    @Synchronized
    fun cancelRunning(scriptId: String) {
        if (!shownStatus.remove(scriptId)) return
        statusIds[scriptId]?.let(manager::cancel)
        if (shownStatus.isEmpty()) manager.cancel(SUMMARY_NOTIFICATION_ID)
    }

    private fun summary(): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.script_status_summary_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openScriptsIntent())
            .setGroup(STATUS_GROUP)
            .setGroupSummary(true)
            .build()

    /** 腳本透過 Lua 的 `device.notify` 發的訊息——與執行狀態分開，不會蓋掉常駐通知。 */
    fun showScriptMessage(title: String, text: String) {
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openScriptsIntent())
            .build()

        notify(messageId++, notification)
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 被拒時不該讓腳本崩掉——腳本本身還是跑得下去。
            Timber.w(e, "Notification not posted; permission missing")
        }
    }

    private fun openScriptsIntent(): PendingIntent {
        val intent = Intent(context, MoonClickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_NAV_TARGET, NAV_TARGET_SCRIPTS)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(scriptId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScriptNotificationReceiver::class.java)
            .setAction(ScriptNotificationReceiver.ACTION_STOP)
            .putExtra(ScriptNotificationReceiver.EXTRA_SCRIPT_ID, scriptId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannels() {
        val service = context.getSystemService(NotificationManager::class.java) ?: return
        service.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                context.getString(R.string.script_status_channel_label),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.script_status_channel_description)
                setShowBadge(false)
            }
        )
        service.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.script_message_channel_label),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.script_message_channel_description)
            }
        )
    }

    private companion object {
        const val STATUS_CHANNEL_ID = "script_status"
        const val MESSAGE_CHANNEL_ID = "script_message"
        const val STATUS_GROUP = "script_status"
        const val SUMMARY_NOTIFICATION_ID = 1000
        const val STATUS_NOTIFICATION_ID_BASE = 1001
        const val MESSAGE_NOTIFICATION_ID_BASE = 2000
    }
}
