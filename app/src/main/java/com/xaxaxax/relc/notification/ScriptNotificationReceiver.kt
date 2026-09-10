package com.xaxaxax.relc.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xaxaxax.relc.script.ScriptManager
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/** Backs the "停止所有" action on the script status notification. */
@AndroidEntryPoint
class ScriptNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scriptManager: ScriptManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_ALL) return
        Timber.d("Stopping all running scripts from notification action")
        scriptManager.stopAllScripts()
    }

    companion object {
        const val ACTION_STOP_ALL = "com.xaxaxax.relc.action.STOP_ALL_SCRIPTS"
    }
}
