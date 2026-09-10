package com.xaxaxax.relc.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xaxaxax.relc.R
import com.xaxaxax.relc.RelcActivity
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Channel and notification ids for the "scripts are running" status notification. */
internal const val SCRIPT_STATUS_CHANNEL_ID = "script_status"
internal const val SCRIPT_STATUS_NOTIFICATION_ID = 1001

/** Set on the [RelcActivity] intent to ask the nav graph to open the Scripts page. */
const val EXTRA_NAV_TARGET = "com.xaxaxax.relc.extra.NAV_TARGET"
const val NAV_TARGET_SCRIPTS = "scripts"

/**
 * Keeps a single persistent notification in sync with [ScriptManager.scriptStates]. It replaces
 * the removed Lua overlay window as the way a user sees that scripts are running, and needs no
 * overlay-window permission — just POST_NOTIFICATIONS.
 *
 * The notification is posted while at least one script is RUNNING and cancelled otherwise.
 */
@Singleton
class ScriptStatusNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scriptManager: ScriptManager,
    private val repository: ScriptRepository,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val notificationManager = NotificationManagerCompat.from(context)

    fun start() {
        createChannel()
        scope.launch {
            combine(repository.scripts, scriptManager.scriptStates) { scripts, states ->
                summarizeRunningScripts(scripts, states)
            }.distinctUntilChanged().collect { summary ->
                if (summary == null) cancel() else post(summary)
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            SCRIPT_STATUS_CHANNEL_ID,
            context.getString(R.string.script_status_channel_label),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.script_status_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun post(summary: RunningScriptsSummary) {
        if (!canPostNotifications()) {
            Timber.d("POST_NOTIFICATIONS not granted; skipping script status notification")
            return
        }
        notificationManager.notify(SCRIPT_STATUS_NOTIFICATION_ID, build(summary))
    }

    private fun cancel() = notificationManager.cancel(SCRIPT_STATUS_NOTIFICATION_ID)

    private fun build(summary: RunningScriptsSummary): Notification {
        val openScripts = openScriptsIntent()
        return NotificationCompat.Builder(context, SCRIPT_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_script)
            .setContentTitle(
                context.getString(R.string.script_status_title, summary.runningCount)
            )
            .setContentText(summary.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.text))
            .setContentIntent(openScripts)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                R.drawable.ic_script,
                context.getString(R.string.script_status_action_view),
                openScripts,
            )
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.script_status_action_stop_all),
                stopAllIntent(),
            )
            .build()
    }

    private fun openScriptsIntent(): PendingIntent {
        val intent = Intent(context, RelcActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_NAV_TARGET, NAV_TARGET_SCRIPTS)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_SCRIPTS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopAllIntent(): PendingIntent {
        val intent = Intent(context, ScriptNotificationReceiver::class.java).apply {
            action = ScriptNotificationReceiver.ACTION_STOP_ALL
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_STOP_ALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_OPEN_SCRIPTS = 1
        const val REQUEST_STOP_ALL = 2
    }
}
