package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * Activity/stack management moved to IActivityTaskManager in Android 10, but AIDL interfaces
 * keep dead overloads around instead of deleting them — verified against the apiMatrix, not
 * guessed. Production code stops calling these past API 28; the platform does not stop
 * having them.
 */
internal val I_ACTIVITY_MANAGER = StubContracts(
    stubs = mapOf("android.app.IActivityManager" to "android.app.IActivityManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.IActivityManager",
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
            note = "Unused by production past API 28, but the platform still has it through API 36 " +
                "(apiMatrix) — no known removal, so left unbounded above.",
        ),
        MemberContract(
            owner = "android.app.IActivityManager",
            member = MethodMember(name = "createStackOnDisplay", parameters = listOf("int"), returns = "int"),
            untilApi = Build.VERSION_CODES.P,
        ),
        MemberContract(
            owner = "android.app.IActivityManager",
            member = MethodMember(
                name = "moveTaskToStack",
                parameters = listOf("int", "int", "boolean"),
                returns = "void",
            ),
            untilApi = Build.VERSION_CODES.R,
            note = "Unused by production past API 28; the platform kept it through API 30 " +
                "(apiMatrix) and removed it by API 31.",
        ),
        // getTasks lost its `flags` parameter in API 28; each side of the split is claimed
        // separately because callers pick the overload by SDK_INT.
        MemberContract(
            owner = "android.app.IActivityManager",
            member = MethodMember(name = "getTasks", parameters = listOf("int"), returns = "java.util.List"),
            sinceApi = Build.VERSION_CODES.P,
        ),
        MemberContract(
            owner = "android.app.IActivityManager",
            member = MethodMember(
                name = "getTasks",
                parameters = listOf("int", "int"),
                returns = "java.util.List",
            ),
            untilApi = Build.VERSION_CODES.O_MR1,
        ),
    ),
)
