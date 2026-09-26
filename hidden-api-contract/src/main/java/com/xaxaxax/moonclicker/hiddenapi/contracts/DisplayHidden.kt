package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val DISPLAY_HIDDEN = StubContracts(
    stubs = mapOf("android.view.DisplayHidden" to "android.view.Display"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.Display",
            member = MethodMember(name = "getType", returns = "int"),
        ),
        MemberContract(
            owner = "android.view.Display",
            member = MethodMember(name = "getLayerStack", returns = "int"),
        ),
        MemberContract(
            owner = "android.view.Display",
            member = MethodMember(
                name = "getDisplayInfo",
                parameters = listOf("android.view.DisplayInfo"),
                returns = "boolean",
            ),
        ),
    ),
)
