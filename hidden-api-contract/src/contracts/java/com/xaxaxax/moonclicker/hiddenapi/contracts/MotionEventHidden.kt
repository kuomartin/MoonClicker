package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val MOTION_EVENT_HIDDEN = StubContracts(
    stubs = mapOf("android.view.MotionEventHidden" to "android.view.MotionEvent"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.MotionEvent",
            member = MethodMember(name = "setDisplayId", parameters = listOf("int"), returns = "void"),
            sinceApi = Build.VERSION_CODES.Q,
        ),
    ),
)
