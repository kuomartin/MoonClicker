package com.xaxaxax.moonclicker.hiddenapi.contracts

/** @hide on API 27–28, @SystemApi from 29; neither is in the SDK's android.jar. */
internal val APP_OPS_MANAGER_OP_ENTRY = StubContracts(
    stubs = mapOf("android.app.AppOpsManager\$OpEntry" to "android.app.AppOpsManager\$OpEntry"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.AppOpsManager\$OpEntry",
            member = MethodMember(name = "getOp", returns = "int"),
        ),
        MemberContract(
            owner = "android.app.AppOpsManager\$OpEntry",
            member = MethodMember(name = "getMode", returns = "int"),
        ),
    ),
)
