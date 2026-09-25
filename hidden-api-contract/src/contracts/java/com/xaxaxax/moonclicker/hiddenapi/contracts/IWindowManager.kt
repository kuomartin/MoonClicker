package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * The overload split MoonClickerService.setDisplayRotation branches on — picking the wrong one
 * throws NoSuchMethodError at runtime. API 27–28 fall outside both and take the
 * shell-command fallback instead.
 */
internal val I_WINDOW_MANAGER = StubContracts(
    stubs = mapOf("android.view.IWindowManager" to "android.view.IWindowManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.IWindowManager",
            member = MethodMember(
                name = "freezeDisplayRotation",
                parameters = listOf("int", "int"),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.Q,
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            note = "API 35 replaced this overload with the `caller` one below.",
        ),
        MemberContract(
            owner = "android.view.IWindowManager",
            member = MethodMember(
                name = "freezeDisplayRotation",
                parameters = listOf("int", "int", "java.lang.String"),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        ),
    ),
)
