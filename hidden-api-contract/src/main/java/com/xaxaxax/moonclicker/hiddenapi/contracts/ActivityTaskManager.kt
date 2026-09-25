package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * Split out of ActivityManager in Android 10; the class does not exist on API 27–28.
 */
internal val ACTIVITY_TASK_MANAGER = StubContracts(
    stubs = mapOf("android.app.ActivityTaskManager" to "android.app.ActivityTaskManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.ActivityTaskManager",
            member = MethodMember(
                name = "getService",
                returns = "android.app.IActivityTaskManager",
                static = true,
            ),
            sinceApi = Build.VERSION_CODES.Q,
        ),
        // Unused by production today; present and matching only from API 31.
        MemberContract(
            owner = "android.app.ActivityTaskManager",
            member = MethodMember(
                name = "getInstance",
                returns = "android.app.ActivityTaskManager",
                static = true,
            ),
            sinceApi = Build.VERSION_CODES.S,
        ),
        MemberContract(
            owner = "android.app.ActivityTaskManager",
            member = MethodMember(
                name = "getTasks",
                parameters = listOf("int"),
                returns = "java.util.List",
            ),
            sinceApi = Build.VERSION_CODES.S,
        ),
    ),
)
