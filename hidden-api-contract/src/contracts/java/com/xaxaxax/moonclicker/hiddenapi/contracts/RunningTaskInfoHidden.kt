package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * From API 29 these live on the TaskInfo superclass; the hierarchy walk covers that.
 */
internal val RUNNING_TASK_INFO_HIDDEN = StubContracts(
    stubs = mapOf(
        "android.app.RunningTaskInfoHidden" to "android.app.ActivityManager\$RunningTaskInfo",
        "android.app.RunningTaskInfoHidden_API_27" to "android.app.ActivityManager\$RunningTaskInfo",
    ),
    contracts = listOf(
        MemberContract(
            owner = "android.app.ActivityManager\$RunningTaskInfo",
            member = FieldMember(name = "id", type = "int"),
        ),
        MemberContract(
            owner = "android.app.ActivityManager\$RunningTaskInfo",
            member = FieldMember(name = "baseActivity", type = "android.content.ComponentName"),
        ),
        MemberContract(
            owner = "android.app.ActivityManager\$RunningTaskInfo",
            member = FieldMember(name = "displayId", type = "int"),
            sinceApi = Build.VERSION_CODES.Q,
            note = "Introduced with TaskInfo in Android 10 — the reason RunningTaskInfoHidden_API_27 " +
                "exists as a separate stub without it.",
        ),
    ),
)
