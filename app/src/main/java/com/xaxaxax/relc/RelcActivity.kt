package com.xaxaxax.relc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.xaxaxax.relc.notification.EXTRA_NAV_TARGET
import com.xaxaxax.relc.notification.NAV_TARGET_SCRIPTS
import com.xaxaxax.relc.notification.ScriptStatusNotifier
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RelcActivity : ComponentActivity() {

    @Inject
    lateinit var scriptStatusNotifier: ScriptStatusNotifier

    /**
     * Set when the activity is opened from the script status notification, so the nav graph
     * jumps to the Scripts page once. Held as state so a notification tap while the activity
     * is already showing ([onNewIntent]) still navigates.
     */
    private val openScriptsPage = mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // A grant here must repost: the running scripts haven't changed, so the
            // notifier's own summary flow would not emit again on its own.
            scriptStatusNotifier.onNotificationPermissionChanged()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNavTarget(intent)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                RelcNavGraph(
                    openScriptsPage = openScriptsPage.value,
                    onScriptsPageOpened = { openScriptsPage.value = false },
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
        if (intent?.getStringExtra(EXTRA_NAV_TARGET) == NAV_TARGET_SCRIPTS) {
            openScriptsPage.value = true
        }
        intent?.removeExtra(EXTRA_NAV_TARGET)
    }

    /**
     * The script status notification is the only place a run is visible while the user is in
     * another app, so ask for POST_NOTIFICATIONS rather than silently dropping it.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
