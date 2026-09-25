package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * The per-display sleep call VirtualDisplayLifecycle uses on a virtual display that owns its
 * display group. Older levels only have the overloads without `displayId`, which act on the
 * default display group.
 */
internal val POWER_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.os.PowerManagerHidden" to "android.os.PowerManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.os.PowerManager",
            member = MethodMember(
                name = "goToSleep",
                parameters = listOf("int", "long", "int", "int"),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
    ),
)
