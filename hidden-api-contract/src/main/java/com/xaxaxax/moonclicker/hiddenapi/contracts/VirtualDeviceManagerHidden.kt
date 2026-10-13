package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val VIRTUAL_DEVICE_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.companion.virtual.VirtualDeviceManagerHidden" to "android.companion.virtual.VirtualDeviceManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.companion.virtual.VirtualDeviceManager",
            member = MethodMember(
                name = "createVirtualDevice",
                parameters = listOf("int", "android.companion.virtual.VirtualDeviceParams"),
                returns = "android.companion.virtual.VirtualDeviceManager\$VirtualDevice",
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
    ),
)
