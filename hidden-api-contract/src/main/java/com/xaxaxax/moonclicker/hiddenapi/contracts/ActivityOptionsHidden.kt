package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val ACTIVITY_OPTIONS_HIDDEN = StubContracts(
    stubs = mapOf("android.app.ActivityOptionsHidden" to "android.app.ActivityOptions"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.ActivityOptions",
            member = MethodMember(
                name = "setLaunchDisplayId",
                parameters = listOf("int"),
                returns = "android.app.ActivityOptions",
            ),
        ),
    ),
)
