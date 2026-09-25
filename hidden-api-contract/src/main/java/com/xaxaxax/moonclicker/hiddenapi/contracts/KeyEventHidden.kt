package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

internal val KEY_EVENT_HIDDEN = StubContracts(
    stubs = mapOf("android.view.KeyEventHidden" to "android.view.KeyEvent"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.KeyEvent",
            member = MethodMember(name = "setDisplayId", parameters = listOf("int"), returns = "void"),
            sinceApi = Build.VERSION_CODES.Q,
        ),
    ),
)
