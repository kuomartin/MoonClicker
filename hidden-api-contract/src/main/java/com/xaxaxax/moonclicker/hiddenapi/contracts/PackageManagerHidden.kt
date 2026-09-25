package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * Nothing calls these today; write a real test before relying on them.
 */
internal val PACKAGE_MANAGER_HIDDEN = StubContracts(
    stubs = mapOf("android.content.pm.PackageManagerHidden" to "android.content.pm.PackageManager"),
    contracts = listOf(
        MemberContract(
            owner = "android.content.pm.PackageManager",
            member = MethodMember(
                name = "grantRuntimePermission",
                parameters = listOf("java.lang.String", "java.lang.String", "android.os.UserHandle"),
                returns = "void",
            ),
        ),
        MemberContract(
            owner = "android.content.pm.PackageManager",
            member = MethodMember(
                name = "addOnPermissionsChangeListener",
                parameters = listOf("android.content.pm.PackageManager\$OnPermissionsChangedListener"),
                returns = "void",
            ),
        ),
        MemberContract(
            owner = "android.content.pm.PackageManager",
            member = MethodMember(
                name = "removeOnPermissionsChangeListener",
                parameters = listOf("android.content.pm.PackageManager\$OnPermissionsChangedListener"),
                returns = "void",
            ),
        ),
        MemberContract(
            owner = "android.content.pm.PackageManager\$OnPermissionsChangedListener",
            member = MethodMember(name = "onPermissionsChanged", parameters = listOf("int"), returns = "void"),
        ),
        // API 35（VANILLA_ICE_CREAM）起有這兩個方法，34（UPSIDE_DOWN_CAKE）沒有——已查證 AOSP
        // 原始碼確認；標成 @Hide、非 @SystemApi。實機驗證見 Pixel 7a（API 37）。
        MemberContract(
            owner = "android.content.pm.PackageManager",
            member = MethodMember(
                name = "registerPackageMonitorCallback",
                parameters = listOf("android.os.IRemoteCallback", "int"),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        ),
        MemberContract(
            owner = "android.content.pm.PackageManager",
            member = MethodMember(
                name = "unregisterPackageMonitorCallback",
                parameters = listOf("android.os.IRemoteCallback"),
                returns = "void",
            ),
            sinceApi = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        ),
    ),
)
