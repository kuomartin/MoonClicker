package com.xaxaxax.moonclicker.service

import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.RunningTaskInfoHidden_API_27
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.xaxaxax.moonclicker.Workaround
import dev.rikka.tools.refine.Refine
import timber.log.Timber

/** 在指定的虛擬顯示上啟動（或搬移）一個已安裝 app。 */
internal class ActivityLauncher(
    private val packageManager: PackageManager,
    private val callerPackage: String,
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
) {
    fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        virtualDisplayLifecycle.wakeDisplayGroupIfOwned(displayId)
        // IActivityManager 的 startActivity/createStackOnDisplay/moveTaskToStack 只到 API 28
        // 為止（見 hidden-api-contract），API 29 起一律走 ActivityTaskManager。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launchViaActivityTaskManager(packageName, displayId)
        } else {
            launchOrMoveViaActivityManager(packageName, displayId)
        }
    }

    /**
     * `startActivity` 的回傳值只抓得到同步的框架錯誤（找不到 activity、權限不足）。
     *
     * 「activity 不支援次要顯示器、被悄悄轉去別台」不在其中：`ActivityStarter` 會把
     * `START_ABORTED` 換成 `START_SUCCESS` 回給呼叫端，該場景只透過 `ITaskStackListener`
     * 非同步通知。要抓它得在啟動後查 task 實際落在哪個顯示器——見 issue #25。
     */
    private fun launchViaActivityTaskManager(packageName: String, displayId: Int): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)
        return try {
            val result = Workaround.startActivity(intent, options, callerPackage)
            Timber.d("ActivityTaskManager.startActivity result = $result")
            checkStartActivityResult(result, intent)
            true
        } catch (t: Throwable) {
            Timber.d(t, "Failed to launch $packageName in display#$displayId.")
            false
        }
    }

    // ── Legacy (API 27–28) ──────────────────────────────────────────────────────────────
    // 只在 Q 以下走到。除了啟動，這條路也涵蓋「app 已經跑在別的顯示器上」——
    // Workaround.startActivity 的 AM 退路不涵蓋，它一律重新啟動。

    private fun launchOrMoveViaActivityManager(packageName: String, displayId: Int): Boolean = runCatching {
        val iam = ActivityManagerHidden.getService()
        val tasks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) iam.getTasks(50) else iam.getTasks(50, 0)
        val task = tasks.find { it.baseActivity?.packageName == packageName }
        if (task != null) {
            val hiddenInfo = Refine.unsafeCast<RunningTaskInfoHidden_API_27>(task)
            Timber.d("moveToDisplay (API <= 28): taskId=${hiddenInfo.id} pkg=$packageName to displayId=$displayId")
            // 只搬這一個 task：在目標顯示器上開一個新 stack，再把 task 移過去。
            val newStackId = iam.createStackOnDisplay(displayId)
            Timber.d("Created stack $newStackId on display $displayId")
            iam.moveTaskToStack(hiddenInfo.id, newStackId, true)
        } else {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("launchInDisplay: no launcher intent for $packageName")
                    .also { Timber.w(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // setLaunchDisplayId is @hide — access via Refine + LSPass
            val options = ActivityOptions.makeBasic()
            Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

            val result = iam.startActivity(
                null, // IApplicationThread
                callerPackage,
                intent,
                null, // resolvedType
                null, // resultTo
                null, // resultWho
                0,    // requestCode
                0,    // flags
                null, // ProfilerInfo
                options.toBundle()
            )
            Timber.d("IActivityManager.startActivity result = $result")
            checkStartActivityResult(result, intent)
        }
    }.onFailure {
        Timber.e(it, "Failed to launch/move $packageName in display#$displayId.")
    }.isSuccess

    private fun checkStartActivityResult(result: Int, intent: Intent) {
        if (result >= 1) return  // ActivityManager.START_SUCCESS and other non-error codes
        when (result) {
            -1, -2 -> throw ActivityNotFoundException(
                "No Activity found to handle $intent"
            )

            -4 -> throw SecurityException(
                "Not allowed to start activity $intent"
            )

            -5 -> throw IllegalArgumentException(
                "PendingIntent is not an activity"
            )

            -6 -> throw RuntimeException(
                "Activity could not be started for $intent"
            )

            -7 -> throw SecurityException(
                "Starting under voice control not allowed for: $intent"
            )

            else -> if (result < 0) throw RuntimeException(
                "Unknown error code $result when starting $intent"
            )
        }
    }
}
