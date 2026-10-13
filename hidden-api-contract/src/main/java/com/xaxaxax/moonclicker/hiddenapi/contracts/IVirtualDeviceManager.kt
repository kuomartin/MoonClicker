package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val I_VIRTUAL_DEVICE_MANAGER = StubContracts(
    stubs = mapOf("android.companion.virtual.IVirtualDeviceManager" to "android.companion.virtual.IVirtualDeviceManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.companion.virtual.IVirtualDeviceManager\$Stub",
            member = MethodMember(
                name = "asInterface",
                parameters = listOf("android.os.IBinder"),
                returns = "android.companion.virtual.IVirtualDeviceManager",
                static = true,
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
    ),
)
