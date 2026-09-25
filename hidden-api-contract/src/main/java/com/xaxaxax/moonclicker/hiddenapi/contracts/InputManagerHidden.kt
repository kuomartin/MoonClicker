package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val INPUT_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.hardware.input.InputManagerHidden" to "android.hardware.input.InputManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.hardware.input.InputManager",
            member = MethodMember(
                name = "injectInputEvent",
                parameters = listOf("android.view.InputEvent", "int"),
                returns = "boolean",
            ),
        ),
    ),
)
