package com.xaxaxax.moonclicker.hiddenapi.contracts

/**
 * The callback LauncherAppsCache hands to `PackageManager.registerPackageMonitorCallback`.
 * MoonClicker subclasses `Stub`, so `Stub` itself is part of the contract, not only the
 * interface. The `Stub()` constructor is not checked here: the suite covers methods and fields.
 */
internal val I_REMOTE_CALLBACK = StubContracts(
    stubs = mapOf("android.os.IRemoteCallback" to "android.os.IRemoteCallback"),
    contracts = listOf(
        MemberContract(
            owner = "android.os.IRemoteCallback",
            member = MethodMember(
                name = "sendResult",
                parameters = listOf("android.os.Bundle"),
                returns = "void",
            ),
        ),
        MemberContract(
            owner = "android.os.IRemoteCallback\$Stub",
            member = MethodMember(name = "asBinder", returns = "android.os.IBinder"),
        ),
    ),
)
