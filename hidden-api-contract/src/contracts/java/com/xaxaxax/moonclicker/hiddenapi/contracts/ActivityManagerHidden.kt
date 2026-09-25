package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val ACTIVITY_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.app.ActivityManagerHidden" to "android.app.ActivityManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.ActivityManager",
            member = MethodMember(
                name = "getService",
                returns = "android.app.IActivityManager",
                static = true,
            ),
        ),
    ),
)
