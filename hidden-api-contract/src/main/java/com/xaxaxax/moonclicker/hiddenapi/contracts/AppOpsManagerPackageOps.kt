package com.xaxaxax.moonclicker.hiddenapi.contracts

/** @hide on API 27–28, @SystemApi from 29; neither is in the SDK's android.jar. */
internal val APP_OPS_MANAGER_PACKAGE_OPS = StubContracts(
    stubs = mapOf("android.app.AppOpsManager\$PackageOps" to "android.app.AppOpsManager\$PackageOps"),
    contracts = listOf(
        MemberContract(
            owner = "android.app.AppOpsManager\$PackageOps",
            member = MethodMember(name = "getOps", returns = "java.util.List"),
        ),
    ),
)
