package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * Replaced by ActivityTaskManager.RootTaskInfo in Android 12; present through API 30 (apiMatrix).
 */
internal val STACK_INFO = StubContracts(
    stubs = mapOf("android.app.ActivityManager\$StackInfo" to "android.app.ActivityManager\$StackInfo"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.ActivityManager\$StackInfo",
            member = FieldMember(name = "displayId", type = "int"),
            untilApi = Build.VERSION_CODES.R,
        ),
        MemberContract(
            owner = "android.app.ActivityManager\$StackInfo",
            member = FieldMember(name = "taskNames", type = "java.lang.String[]"),
            untilApi = Build.VERSION_CODES.R,
        ),
    ),
)
