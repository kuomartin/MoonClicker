package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * The wake lock calls DisplayGroupWakeLocks makes on the power service directly, so the wake
 * lock carries the package name the calling uid owns. Only the API 33+ `acquireWakeLock`
 * shape is declared; API 27–32 have a shorter one MoonClicker never calls.
 */
internal val I_POWER_MANAGER = StubContracts(
    stubs = mapOf(
        "android.os.IPowerManager" to "android.os.IPowerManager",
        "android.os.IWakeLockCallback" to "android.os.IWakeLockCallback",
    ),
    contracts = listOf(
        MemberContract(
            owner = "android.os.IPowerManager",
            member = MethodMember(
                name = "acquireWakeLock",
                parameters = listOf(
                    "android.os.IBinder",
                    "int",
                    "java.lang.String",
                    "java.lang.String",
                    "android.os.WorkSource",
                    "java.lang.String",
                    "int",
                    "android.os.IWakeLockCallback",
                ),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.TIRAMISU,
        ),
        MemberContract(
            owner = "android.os.IPowerManager",
            member = MethodMember(
                name = "releaseWakeLock",
                parameters = listOf("android.os.IBinder", "int"),
                returns = "void",
            ),
        ),
        MemberContract(
            owner = "android.os.IPowerManager\$Stub",
            member = MethodMember(
                name = "asInterface",
                parameters = listOf("android.os.IBinder"),
                returns = "android.os.IPowerManager",
                static = true,
            ),
        ),
    ),
)
