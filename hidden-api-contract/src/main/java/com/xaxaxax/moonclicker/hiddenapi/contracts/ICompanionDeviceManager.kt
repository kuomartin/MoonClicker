package com.xaxaxax.moonclicker.hiddenapi.contracts

/** VirtualDevices 用它找出 shell 持有的 association id。 */
internal val I_COMPANION_DEVICE_MANAGER = StubContracts(
    stubs = mapOf("android.companion.ICompanionDeviceManager" to "android.companion.ICompanionDeviceManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.companion.ICompanionDeviceManager",
            member = MethodMember(
                name = "getAssociations",
                parameters = listOf("java.lang.String", "int"),
                returns = "java.util.List",
            ),
            note = "API 33 前元素是 String 而非 AssociationInfo，只在 33+ 呼叫。",
        ),
        MemberContract(
            owner = "android.companion.ICompanionDeviceManager\$Stub",
            member = MethodMember(
                name = "asInterface",
                parameters = listOf("android.os.IBinder"),
                returns = "android.companion.ICompanionDeviceManager",
                static = true,
            ),
        ),
    ),
)
