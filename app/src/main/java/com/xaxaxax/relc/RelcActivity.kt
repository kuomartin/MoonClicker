package com.xaxaxax.relc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.xaxaxax.relc.notification.EXTRA_NAV_TARGET
import com.xaxaxax.relc.notification.NAV_TARGET_SCRIPTS
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RelcActivity : ComponentActivity() {

    /**
     * Route the nav graph should jump to once, set when the activity is opened from the
     * script status notification. Held as state so a notification tap while the activity
     * is already showing ([onNewIntent]) still navigates.
     */
    private val pendingNavTarget = mutableStateOf<Any?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNavTarget(intent)
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                RelcNavGraph(
                    pendingNavTarget = pendingNavTarget.value,
                    onNavTargetHandled = { pendingNavTarget.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavTarget(intent)
    }

    private fun consumeNavTarget(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_NAV_TARGET)) {
            NAV_TARGET_SCRIPTS -> pendingNavTarget.value = ScriptsRoute
        }
        intent?.removeExtra(EXTRA_NAV_TARGET)
    }
}
