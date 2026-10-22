package com.xaxaxax.moonclicker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xaxaxax.moonclicker.core.AppLocale
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.ui.theme.MoonClickerTheme
import com.xaxaxax.moonclicker.update.UpdateChecker
import com.xaxaxax.moonclicker.update.UpdateInstaller
import com.xaxaxax.moonclicker.workbench.WorkbenchService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject


/** Set on the [MoonClickerActivity] intent to ask the nav graph to open a top-level page. */
const val EXTRA_NAV_TARGET = "com.xaxaxax.moonclicker.extra.NAV_TARGET"
const val NAV_TARGET_SCRIPTS = "scripts"
const val NAV_TARGET_SETTINGS = "settings"

/** Set together with [NAV_TARGET_SETTINGS] by the "update downloaded" notification to start installing. */
const val EXTRA_INSTALL_UPDATE = "com.xaxaxax.moonclicker.extra.INSTALL_UPDATE"
@AndroidEntryPoint
class MoonClickerActivity : ComponentActivity() {
    /**
     * Set when the activity is opened from a notification, so the nav graph jumps to that page
     * once. Held as state so a notification tap while the activity is already showing
     * ([onNewIntent]) still navigates.
     */
    private val navTarget = mutableStateOf<String?>(null)

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var updateInstaller: UpdateInstaller

    /**
     * Android 13+ 的語言偏好是系統的 LocaleManager 在管，OS 自己會處理 Activity 重建；
     * 13 以下沒有這套機制，且我們沒有 AppCompatActivity 可以借，只能自己包一層 Context。
     */
    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.attachBaseContext(newBase)
        } else {
            super.attachBaseContext(AppLocale.wrap(newBase, AppSettings.readAppLanguageTag(newBase)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reconcileWorkbenchState()
        // 重建（行程被殺後從 Recents 回來）時系統會重送當初的 intent，removeExtra 改不到那份；
        // 通知要的動作已經做過，不能再導頁或再叫出安裝畫面。
        val relaunched = savedInstanceState != null ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!relaunched) consumeNavTarget(intent)
        enableEdgeToEdge()
        val startDestination = appSettings.defaultStartPage.value.route
        setContent {
            val hasPendingUpdate by updateChecker.hasPendingUpdate.collectAsState()
            MoonClickerTheme {
                MoonClickerNavGraph(
                    startDestination = startDestination,
                    navTarget = navTarget.value,
                    onNavTargetOpened = { navTarget.value = null },
                    settingsBadge = hasPendingUpdate,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { updateChecker.checkIfDue() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavTarget(intent)
    }

    /**
     * `workbenchEnabled` 只是持久化偏好，實際的前景服務可能被系統在背景殺掉而不會自動重啟
     * （`START_STICKY` 不保證），所以每次打開 App 都依偏好值重新對帳一次。
     */
    private fun reconcileWorkbenchState() {
        if (!appSettings.workbenchEnabled.value) return
        ContextCompat.startForegroundService(this, Intent(this, WorkbenchService::class.java))
    }

    private fun consumeNavTarget(intent: Intent?) {
        intent?.getStringExtra(EXTRA_NAV_TARGET)?.let { navTarget.value = it }
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) updateInstaller.requestInstall()
        intent?.removeExtra(EXTRA_NAV_TARGET)
        intent?.removeExtra(EXTRA_INSTALL_UPDATE)
    }
}
