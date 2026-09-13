package com.xaxaxax.relc.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xaxaxax.relc.script.ScriptSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 常駐通知上的「停止」。 */
@AndroidEntryPoint
class ScriptNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var session: ScriptSession

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        session.stop()
    }

    companion object {
        const val ACTION_STOP = "com.xaxaxax.relc.action.STOP_SCRIPT"
    }
}
