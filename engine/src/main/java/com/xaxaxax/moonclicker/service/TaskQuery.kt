package com.xaxaxax.moonclicker.service

import android.app.ActivityManagerHidden
import android.app.RunningTaskInfoHidden
import android.content.ComponentName
import android.os.Build
import com.xaxaxax.moonclicker.MoonClickerAppTask
import dev.rikka.tools.refine.Refine

/**
 * 各顯示器上有 task 的 app。
 *
 * package 取 task 的 root activity：app 在自己的 task 裡開了別家的 activity（分享、瀏覽器）
 * 時，top activity 會把那個 task 算成別家的。launcher、SystemUI 等一律不過濾——哪些算「app」
 * 是腳本的判斷，而過濾名單會隨 ROM 變。
 */
internal class TaskQuery {
    fun getAppTasks(): Array<MoonClickerAppTask> {
        val pairs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fromTasks() else fromStacks()
        return pairs.distinct().map { (packageName, displayId) ->
            MoonClickerAppTask().apply {
                this.packageName = packageName
                this.displayId = displayId
            }
        }.toTypedArray()
    }

    /** 單參數的 getTasks 在 API 29+ 轉給 ActivityTaskManager，涵蓋所有顯示器。 */
    private fun fromTasks(): List<Pair<String, Int>> =
        ActivityManagerHidden.getService().getTasks(Int.MAX_VALUE).mapNotNull { task ->
            val packageName = task.baseActivity?.packageName ?: return@mapNotNull null
            packageName to Refine.unsafeCast<RunningTaskInfoHidden>(task).displayId
        }

    /** API 27–28 的 RunningTaskInfo 沒有 displayId，只有 stack 知道自己在哪個顯示器上。 */
    private fun fromStacks(): List<Pair<String, Int>> =
        ActivityManagerHidden.getService().allStackInfos.flatMap { stack ->
            stack.taskNames.orEmpty().map { name ->
                // 沒有 root component 的 task，框架填的是 top activity 的 package name。
                (ComponentName.unflattenFromString(name)?.packageName ?: name) to stack.displayId
            }
        }
}
