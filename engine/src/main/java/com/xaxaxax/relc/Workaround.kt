package com.xaxaxax.relc

import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.Intent
import android.os.Build

object Workaround {
    /**
     * @param callerPackage 向系統宣稱的呼叫者。ActivityTaskManager 會拿它跟 calling uid
     *   對，所以它必須是**執行這段程式碼的 uid 真的擁有的**套件名——在 Shizuku 的 shell
     *   進程裡是 `com.android.shell`，換個宿主進程就不是了。
     */
    fun startActivity(
        intent: Intent,
        options: ActivityOptions,
        callerPackage: String,
    ) = when(Build.VERSION.SDK_INT) {
        // use ATM with 11 params
        in Build.VERSION_CODES.R..Int.MAX_VALUE ->
        ActivityTaskManager.getService().startActivity(
            null,
            callerPackage,
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
                callerPackage,
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
            callerPackage,
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