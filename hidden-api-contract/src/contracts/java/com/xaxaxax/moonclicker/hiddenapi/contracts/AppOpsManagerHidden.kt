package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val APP_OPS_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.app.AppOpsManagerHidden" to "android.app.AppOpsManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.AppOpsManager",
            member = MethodMember(
                name = "strOpToOp",
                parameters = listOf("java.lang.String"),
                returns = "int",
                static = true,
            ),
        ),
        MemberContract(
            owner = "android.app.AppOpsManager",
            member = MethodMember(
                name = "setMode",
                parameters = listOf("int", "int", "java.lang.String", "int"),
                returns = "void",
            ),
        ),
    ),
)
