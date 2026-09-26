package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/** VirtualDisplayLifecycle reads which display group a new virtual display actually landed in. */
internal val DISPLAY_INFO = StubContracts(
    stubs = mapOf("android.view.DisplayInfo" to "android.view.DisplayInfo"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.DisplayInfo",
            member = FieldMember(name = "displayGroupId", type = "int"),
            sinceApi = Build.VERSION_CODES.S,
        ),
    ),
)
