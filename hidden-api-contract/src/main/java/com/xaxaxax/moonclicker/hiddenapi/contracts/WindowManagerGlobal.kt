package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val WINDOW_MANAGER_GLOBAL = StubContracts(
    stubs = mapOf("android.view.WindowManagerGlobal" to "android.view.WindowManagerGlobal"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.WindowManagerGlobal",
            member = MethodMember(
                name = "getWindowManagerService",
                returns = "android.view.IWindowManager",
                static = true,
            ),
            note = "MoonClickerService calls this statically; a move to an instance method would break " +
                "the call site even though the name still resolves.",
        ),
        MemberContract(
            owner = "android.view.WindowManagerGlobal",
            member = MethodMember(
                name = "getInstance",
                returns = "android.view.WindowManagerGlobal",
                static = true,
            ),
        ),
    ),
)
