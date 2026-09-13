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
import com.xaxaxax.relc.ui.theme.ReLCTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


/** Set on the [RelcActivity] intent to ask the nav graph to open the Scripts page. */
const val EXTRA_NAV_TARGET = "com.xaxaxax.relc.extra.NAV_TARGET"
const val NAV_TARGET_SCRIPTS = "scripts"
@AndroidEntryPoint
class RelcActivity : ComponentActivity() {
    /**
     * Set when the activity is opened from the script status notification, so the nav graph
     * jumps to the Scripts page once. Held as state so a notification tap while the activity
     * is already showing ([onNewIntent]) still navigates.
     */
    private val openScriptsPage = mutableStateOf(false)


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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
