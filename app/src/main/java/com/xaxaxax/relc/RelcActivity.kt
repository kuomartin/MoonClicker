package com.xaxaxax.relc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.xaxaxax.relc.core.AppLocale
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.ui.theme.ReLCTheme
import com.xaxaxax.relc.workbench.WorkbenchService
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

    /** 決定這次打開 App 要不要把 UserService 啟動起來。 */
    private val autoStarter: UserServiceAutoStarter by viewModels()

    @Inject
    lateinit var appSettings: AppSettings

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
        autoStarter.onAppOpened()
        reconcileWorkbenchState()
        consumeNavTarget(intent)
        enableEdgeToEdge()
        setContent {
            ReLCTheme {
                RelcNavGraph(
                    startDestination = appSettings.defaultStartPage.value.route,
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

    /**
     * `workbenchEnabled` 只是持久化偏好，實際的前景服務可能被系統在背景殺掉而不會自動重啟
     * （`START_STICKY` 不保證），所以每次打開 App 都依偏好值重新對帳一次——比照
     * [UserServiceAutoStarter.onAppOpened] 對 Shizuku user service 的作法。
     */
    private fun reconcileWorkbenchState() {
        if (!appSettings.workbenchEnabled.value) return
        ContextCompat.startForegroundService(this, Intent(this, WorkbenchService::class.java))
    }

    private fun consumeNavTarget(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_NAV_TARGET) == NAV_TARGET_SCRIPTS) {
            openScriptsPage.value = true
        }
        intent?.removeExtra(EXTRA_NAV_TARGET)
    }
}
