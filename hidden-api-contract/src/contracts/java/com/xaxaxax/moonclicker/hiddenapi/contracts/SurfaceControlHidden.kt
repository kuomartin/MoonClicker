package com.xaxaxax.moonclicker.hiddenapi.contracts

import android.os.Build

/**
 * 沒有 `createVirtualDisplay(..., displayId, surface)` 重載的 API 上，MoonClickerService
 * 改用 SurfaceControl 直接把 layer stack 接到來源螢幕（比照 scrcpy）。stub 標
 * `@DeprecatedSinceApi(UPSIDE_DOWN_CAKE)`——production code 不再用它。實測（apiMatrix）：
 * display 相關的六個方法在 34（UPSIDE_DOWN_CAKE）之後真的被移除；`openTransaction`／
 * `closeTransaction` 是共用的 transaction 生命週期方法，36（BAKLAVA）仍在，故不設上界。
 */
internal val SURFACE_CONTROL_HIDDEN = StubContracts(
    stubs = mapOf("android.view.SurfaceControlHidden" to "android.view.SurfaceControl"),
    contracts = listOf(
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(
                name = "createDisplay",
                parameters = listOf("java.lang.String", "boolean"),
                returns = "android.os.IBinder",
                static = true,
            ),
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(name = "openTransaction", returns = "void", static = true),
            note = "Unused by production past API 33, but still present through API 36 (apiMatrix) — " +
                "no known removal, so left unbounded above.",
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(name = "closeTransaction", returns = "void", static = true),
            note = "Unused by production past API 33, but still present through API 36 (apiMatrix) — " +
                "no known removal, so left unbounded above.",
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(
                name = "setDisplaySurface",
                parameters = listOf("android.os.IBinder", "android.view.Surface"),
                returns = "void",
                static = true,
            ),
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(
                name = "setDisplayLayerStack",
                parameters = listOf("android.os.IBinder", "int"),
                returns = "void",
                static = true,
            ),
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(
                name = "setDisplayProjection",
                parameters = listOf(
                    "android.os.IBinder",
                    "int",
                    "android.graphics.Rect",
                    "android.graphics.Rect",
                ),
                returns = "void",
                static = true,
            ),
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
        MemberContract(
            owner = "android.view.SurfaceControl",
            member = MethodMember(
                name = "destroyDisplay",
                parameters = listOf("android.os.IBinder"),
                returns = "void",
                static = true,
            ),
            untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ),
    ),
)
