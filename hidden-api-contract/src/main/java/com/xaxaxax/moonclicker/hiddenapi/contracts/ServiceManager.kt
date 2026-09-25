package com.xaxaxax.moonclicker.hiddenapi.contracts

internal val SERVICE_MANAGER = StubContracts(
    stubs = mapOf("android.os.ServiceManager" to "android.os.ServiceManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.os.ServiceManager",
            member = MethodMember(
                name = "getService",
                parameters = listOf("java.lang.String"),
                returns = "android.os.IBinder",
                static = true,
            ),
        ),
    ),
)
