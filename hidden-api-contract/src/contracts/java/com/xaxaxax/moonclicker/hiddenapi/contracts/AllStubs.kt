package com.xaxaxax.moonclicker.hiddenapi.contracts

/**
 * Every method and field `:hidden-api` declares, restated as a claim about the platform.
 *
 * The stubs in `:hidden-api` are assumptions: we declare that a `@hide` member exists with a
 * given signature, compile against the stub, and resolve to the real framework class at
 * runtime. Nothing verifies that but these contracts. Each stub file has a matching
 * `<Stub>.kt` here, registered below; `StubCoverageTest` fails when a stub member has
 * no contract or the two disagree on shape.
 *
 * Compile-time constants are not here — javac inlines them into callers, so verifying them
 * needs the stub's baked-in value rather than a reflective lookup. See [StubConstantTable].
 */
internal val ALL_STUB_CONTRACTS: List<StubContracts> = listOf(
    ACTIVITY_MANAGER_HIDDEN,
    ACTIVITY_OPTIONS_HIDDEN,
    ACTIVITY_TASK_MANAGER,
    APP_OPS_MANAGER_HIDDEN,
    I_ACTIVITY_MANAGER,
    I_ACTIVITY_TASK_MANAGER,
    // Does not need to check IApplicationThread, ProfilerInfo
    RUNNING_TASK_INFO_HIDDEN, // Including API 27
    PACKAGE_MANAGER_HIDDEN,
    DISPLAY_MANAGER_HIDDEN,
    INPUT_MANAGER_HIDDEN,
    DISPLAY_HIDDEN,
    I_WINDOW_MANAGER,
    KEY_EVENT_HIDDEN,
    MOTION_EVENT_HIDDEN,
    SURFACE_CONTROL_HIDDEN,
    WINDOW_MANAGER_GLOBAL,
)
