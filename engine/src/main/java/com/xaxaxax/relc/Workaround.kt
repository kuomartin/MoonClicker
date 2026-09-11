package com.xaxaxax.relc

import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.Intent
import android.os.Build

object Workaround {
    fun startActivity(intent: Intent, options: ActivityOptions) = when(Build.VERSION.SDK_INT) {
        // use ATM with 11 params
        in Build.VERSION_CODES.R..Int.MAX_VALUE ->
        ActivityTaskManager.getService().startActivity(
            null,
            "com.android.shell",
            null,
            intent,
            null,
            null,
            null,
            0,
            0,
            null,
            options.toBundle()
        )
        // use ATM with 10 params
        Build.VERSION_CODES.Q ->
            ActivityTaskManager.getService().startActivity(
                null,
                "com.android.shell",
                intent,
                null,
                null,
                null,
                0,
                0,
                null,
                options.toBundle()
            )

        // use AM
        else ->
        ActivityManagerHidden.getService().startActivity(
            null,
            "com.android.shell",
            intent,
            null,
            null,
            null,
            0,
            0,
            null,
            options.toBundle()
        )
    }
}