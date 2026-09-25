package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val DISPLAY_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.hardware.display.DisplayManagerHidden" to "android.hardware.display.DisplayManager"),
    contracts = listOf(
        // The mirror overload DisplayMirroring uses from API 34; older levels mirror through
        // SurfaceControl instead.
        MemberContract(
            owner = "android.hardware.display.DisplayManager",
            member = MethodMember(
                name = "createVirtualDisplay",
                parameters = listOf(
                    "java.lang.String",
                    "int",
                    "int",
                    "int",
                    "android.view.Surface",
                ),
                returns = "android.hardware.display.VirtualDisplay",
                static = true,
            ),
            sinceApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
        MemberContract(
            owner = "android.hardware.display.DisplayManager",
            member = MethodMember(
                name = "createVirtualDisplay",
                parameters = listOf(
                    "java.lang.String",
                    "int",
                    "int",
                    "int",
                    "android.view.Surface",
                    "int",
                ),
                returns = "android.hardware.display.VirtualDisplay",
            ),
        ),
    ),
)
