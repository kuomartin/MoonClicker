package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val VIRTUAL_DEVICE_PARAMS = StubContracts(
    stubs = mapOf("android.companion.virtual.VirtualDeviceParams" to "android.companion.virtual.VirtualDeviceParams"),
    contracts = listOf(
        MemberContract(
            owner = "android.companion.virtual.VirtualDeviceParams\$Builder",
            member = MethodMember(
                name = "setLockState",
                parameters = listOf("int"),
                returns = "android.companion.virtual.VirtualDeviceParams\$Builder",
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
        MemberContract(
            owner = "android.companion.virtual.VirtualDeviceParams\$Builder",
            member = MethodMember(
                name = "build",
                returns = "android.companion.virtual.VirtualDeviceParams",
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
    ),
)
