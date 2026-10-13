package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val VIRTUAL_DEVICE_MANAGER_VIRTUAL_DEVICE = StubContracts(
    stubs = mapOf(
        "android.companion.virtual.VirtualDeviceManager\$VirtualDevice" to
            "android.companion.virtual.VirtualDeviceManager\$VirtualDevice",
    ),
    contracts = listOf(
        MemberContract(
            owner = "android.companion.virtual.VirtualDeviceManager\$VirtualDevice",
            member = MethodMember(
                name = "createVirtualDisplay",
                parameters = listOf(
                    "int",
                    "int",
                    "int",
                    "android.view.Surface",
                    "int",
                    "java.util.concurrent.Executor",
                    "android.hardware.display.VirtualDisplay\$Callback",
                ),
                returns = "android.hardware.display.VirtualDisplay",
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
        MemberContract(
            owner = "android.companion.virtual.VirtualDeviceManager\$VirtualDevice",
            member = MethodMember(name = "close", returns = "void"),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
    ),
)
