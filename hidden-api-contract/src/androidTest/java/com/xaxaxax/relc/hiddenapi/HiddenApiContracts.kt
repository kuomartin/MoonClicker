package com.xaxaxax.relc.hiddenapi

import android.os.Build

/**
 * Every method and field `:hidden-api` declares, restated as a claim about the platform.
 *
 * The stubs in `:hidden-api` are assumptions: we declare that a `@hide` member exists with a
 * given signature, compile against the stub, and resolve to the real framework class at
 * runtime. Nothing verifies that but this table. Entries are grouped by stub file so the two
 * can be diffed by eye; when a stub gains a member, it belongs here too.
 *
 * The `VIRTUAL_DISPLAY_FLAG_*` constants are deliberately *not* here — they are compile-time
 * constants that javac inlines into callers, so verifying them needs the stub's baked-in value
 * rather than a reflective lookup. See [VirtualDisplayFlagContractTest].
 */
internal val HIDDEN_API_CONTRACTS: List<MemberContract> = listOf(

    // ── ActivityManagerHidden ──────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.app.ActivityManager",
        member = MethodMember(
            name = "getService",
            returns = "android.app.IActivityManager",
            static = true,
        ),
    ),

    // ── ActivityOptionsHidden ──────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.app.ActivityOptions",
        member = MethodMember(
            name = "setLaunchDisplayId",
            parameters = listOf("int"),
            returns = "android.app.ActivityOptions",
        ),
    ),

    // ── ActivityTaskManager ────────────────────────────────────────────────────────────────
    // Split out of ActivityManager in Android 10; the class does not exist on API 27–28.
    MemberContract(
        owner = "android.app.ActivityTaskManager",
        member = MethodMember(
            name = "getService",
            returns = "android.app.IActivityTaskManager",
            static = true,
        ),
        sinceApi = Build.VERSION_CODES.Q,
    ),
    MemberContract(
        owner = "android.app.ActivityTaskManager",
        member = MethodMember(
            name = "getInstance",
            returns = "android.app.ActivityTaskManager",
            static = true,
        ),
        sinceApi = Build.VERSION_CODES.Q,
    ),
    MemberContract(
        owner = "android.app.ActivityTaskManager",
        member = MethodMember(
            name = "getTasks",
            parameters = listOf("int"),
            returns = "java.util.List",
        ),
        sinceApi = Build.VERSION_CODES.Q,
    ),

    // ── AppOpsManagerHidden ────────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.app.AppOpsManager",
        member = MethodMember(
            name = "strOpToOp",
            parameters = listOf("java.lang.String"),
            returns = "int",
            static = true,
        ),
    ),
    MemberContract(
        owner = "android.app.AppOpsManager",
        member = MethodMember(
            name = "setMode",
            parameters = listOf("int", "int", "java.lang.String", "int"),
            returns = "void",
        ),
    ),

    // ── IActivityManager ───────────────────────────────────────────────────────────────────
    // The V1 (RelcShizukuService) launch path. Activity/stack management moved to
    // IActivityTaskManager in Android 10, so these are 27–28 only.
    MemberContract(
        owner = "android.app.IActivityManager",
        member = MethodMember(
            name = "startActivity",
            parameters = listOf(
                "android.app.IApplicationThread",
                "java.lang.String",
                "android.content.Intent",
                "java.lang.String",
                "android.os.IBinder",
                "java.lang.String",
                "int",
                "int",
                "android.app.ProfilerInfo",
                "android.os.Bundle",
            ),
            returns = "int",
        ),
        untilApi = Build.VERSION_CODES.P,
    ),
    MemberContract(
        owner = "android.app.IActivityManager",
        member = MethodMember(name = "createStackOnDisplay", parameters = listOf("int"), returns = "int"),
        untilApi = Build.VERSION_CODES.P,
    ),
    MemberContract(
        owner = "android.app.IActivityManager",
        member = MethodMember(
            name = "moveTaskToStack",
            parameters = listOf("int", "int", "boolean"),
            returns = "void",
        ),
        untilApi = Build.VERSION_CODES.P,
    ),
    // getTasks lost its `flags` parameter in API 28 — the stub carries both overloads and
    // RelcShizukuService picks by SDK_INT, so each side of the split is claimed separately.
    MemberContract(
        owner = "android.app.IActivityManager",
        member = MethodMember(name = "getTasks", parameters = listOf("int"), returns = "java.util.List"),
        sinceApi = Build.VERSION_CODES.P,
    ),
    MemberContract(
        owner = "android.app.IActivityManager",
        member = MethodMember(
            name = "getTasks",
            parameters = listOf("int", "int"),
            returns = "java.util.List",
        ),
        untilApi = Build.VERSION_CODES.O_MR1,
    ),

    // ── IActivityTaskManager ───────────────────────────────────────────────────────────────
    // `callingFeatureId` was inserted in API 30; API 29 has the shorter overload.
    MemberContract(
        owner = "android.app.IActivityTaskManager",
        member = MethodMember(
            name = "startActivity",
            parameters = listOf(
                "android.app.IApplicationThread",
                "java.lang.String",
                "java.lang.String",
                "android.content.Intent",
                "java.lang.String",
                "android.os.IBinder",
                "java.lang.String",
                "int",
                "int",
                "android.app.ProfilerInfo",
                "android.os.Bundle",
            ),
            returns = "int",
        ),
        sinceApi = Build.VERSION_CODES.R,
    ),
    MemberContract(
        owner = "android.app.IActivityTaskManager",
        member = MethodMember(
            name = "startActivity",
            parameters = listOf(
                "android.app.IApplicationThread",
                "java.lang.String",
                "android.content.Intent",
                "java.lang.String",
                "android.os.IBinder",
                "java.lang.String",
                "int",
                "int",
                "android.app.ProfilerInfo",
                "android.os.Bundle",
            ),
            returns = "int",
        ),
        sinceApi = Build.VERSION_CODES.Q,
        untilApi = Build.VERSION_CODES.Q,
    ),

    // ── PackageManagerHidden ───────────────────────────────────────────────────────────────
    // NOTE: the two listener methods are checked against the *platform's* nested type,
    // android.content.pm.PackageManager$OnPermissionsChangedListener. The stub declares its own
    // PackageManagerHidden$OnPermissionsChangedListener instead, and Refine only remaps types
    // carrying a $RefineMetadata marker — which a nested type of a @RefineAs class does not get.
    // So a call through the stub would emit an unresolvable descriptor. Nothing calls these
    // today; the entries below pin the platform side, and the stub needs fixing before anything
    // does. See the issue #18 discussion.
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

    // ── InputManagerHidden ─────────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.hardware.input.InputManager",
        member = MethodMember(
            name = "injectInputEvent",
            parameters = listOf("android.view.InputEvent", "int"),
            returns = "boolean",
        ),
    ),

    // ── MotionEventHidden ──────────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.view.MotionEvent",
        member = MethodMember(name = "setDisplayId", parameters = listOf("int"), returns = "void"),
    ),

    // ── IWindowManager ─────────────────────────────────────────────────────────────────────
    // The overload split RelcV2Service.setDisplayRotation branches on, and the reason issue #18
    // was opened: picking the wrong overload throws NoSuchMethodError at runtime. Each side
    // states the range it must exist on; API 27–28 fall outside both and take the shell-command
    // fallback instead.
    MemberContract(
        owner = "android.view.IWindowManager",
        member = MethodMember(
            name = "freezeDisplayRotation",
            parameters = listOf("int", "int"),
            returns = "void",
        ),
        sinceApi = Build.VERSION_CODES.Q,
        untilApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        note = "API 35 replaced this overload with the `caller` one below.",
    ),
    MemberContract(
        owner = "android.view.IWindowManager",
        member = MethodMember(
            name = "freezeDisplayRotation",
            parameters = listOf("int", "int", "java.lang.String"),
            returns = "void",
        ),
        sinceApi = Build.VERSION_CODES.VANILLA_ICE_CREAM,
    ),

    // ── WindowManagerGlobal ────────────────────────────────────────────────────────────────
    MemberContract(
        owner = "android.view.WindowManagerGlobal",
        member = MethodMember(
            name = "getWindowManagerService",
            returns = "android.view.IWindowManager",
            static = true,
        ),
        note = "RelcV2Service calls this statically; a move to an instance method would break " +
            "the call site even though the name still resolves.",
    ),
    MemberContract(
        owner = "android.view.WindowManagerGlobal",
        member = MethodMember(
            name = "getInstance",
            returns = "android.view.WindowManagerGlobal",
            static = true,
        ),
    ),

    // ── RunningTaskInfoHidden / RunningTaskInfoHidden_API_27 ───────────────────────────────
    // From API 29 these live on the TaskInfo superclass; the hierarchy walk covers that.
    MemberContract(
        owner = "android.app.ActivityManager\$RunningTaskInfo",
        member = FieldMember(name = "id", type = "int"),
    ),
    MemberContract(
        owner = "android.app.ActivityManager\$RunningTaskInfo",
        member = FieldMember(name = "baseActivity", type = "android.content.ComponentName"),
    ),
    MemberContract(
        owner = "android.app.ActivityManager\$RunningTaskInfo",
        member = FieldMember(name = "displayId", type = "int"),
        sinceApi = Build.VERSION_CODES.Q,
        note = "Introduced with TaskInfo in Android 10 — the reason RunningTaskInfoHidden_API_27 " +
            "exists as a separate stub without it.",
    ),
)
