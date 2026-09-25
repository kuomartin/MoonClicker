package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * `callingFeatureId` was inserted in API 30; API 29 has the shorter overload.
 */
internal val I_ACTIVITY_TASK_MANAGER = StubContracts(
    stubs = mapOf("android.app.IActivityTaskManager" to "android.app.IActivityTaskManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.IActivityTaskManager",
            member = MethodMember(
                name = "startActivity",
                parameters = listOf(
                    "android.app.IApplicationThread",
                    "java.lang.String",
                    "java.lang.String",
                    "android.content.Intent",
                    "java.lang.String",
                    "android.os.IBinder",
                    "java.lang.String",
                    "int",
                    "int",
                    "android.app.ProfilerInfo",
                    "android.os.Bundle",
                ),
                returns = "int",
            ),
            sinceApi = Build.VERSION_CODES.R,
        ),
        MemberContract(
            owner = "android.app.IActivityTaskManager",
            member = MethodMember(
                name = "startActivity",
                parameters = listOf(
                    "android.app.IApplicationThread",
                    "java.lang.String",
                    "android.content.Intent",
                    "java.lang.String",
                    "android.os.IBinder",
                    "java.lang.String",
                    "int",
                    "int",
                    "android.app.ProfilerInfo",
                    "android.os.Bundle",
                ),
                returns = "int",
            ),
            sinceApi = Build.VERSION_CODES.Q,
            untilApi = Build.VERSION_CODES.Q,
        ),
    ),
)
