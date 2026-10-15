package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val UI_AUTOMATION_HIDDEN = StubContracts(
    stubs = mapOf(
        "android.app.UiAutomationHidden" to "android.app.UiAutomation",
        "android.app.UiAutomationConnection" to "android.app.UiAutomationConnection",
    ),
    contracts = listOf(
        MemberContract(
            owner = "android.app.UiAutomation",
            member = MethodMember(name = "connect", parameters = listOf("int"), returns = "void"),
        ),
        MemberContract(
            owner = "android.app.UiAutomation",
            member = MethodMember(name = "disconnect", returns = "void"),
        ),
        MemberContract(
            owner = "android.app.UiAutomationConnection",
            member = MethodMember(name = "asBinder", returns = "android.os.IBinder"),
        ),
    ),
)
