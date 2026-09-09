# Split into :engine/:overlay/:simplescript/:app, with :engine as the sole native-access boundary

The app was a single `:app` module where Overlay UI and `FullscreenDisplayActivity` each reached directly into the JNI bridge (`LuaNative`) and into a global `OverlayServiceProvider` singleton, and where the only visible engine state was a 500ms poll of a single `isEngineRunning()` boolean. We're splitting into `:engine` (owns the RelcEngine/LuaNative JNI bridge and RelcV2Service/Shizuku, the only module allowed to touch native internals — `LuaNative` becomes module-internal, not exported), `:overlay` and `:simplescript` (both depend only on `:engine`, never on each other), and `:app` as the composition root that wires them together (e.g. hosting `simplescript`'s nav graph as a bottom sheet from `FullscreenDisplayActivity`). `:engine` exposes a read-only `EngineStateRepository` (a single `StateFlow<EngineState>` aggregating run state, per-virtual-display state, latest match result, and error detail), fed by a new single JNI upcall channel `onEngineEvent(eventType, payload)` — replacing the pattern of adding a new per-purpose `jmethodID` upcall for every new bit of state. `OverlayServiceProvider`'s static singleton is replaced with Hilt-scoped DI.

Legacy `script.simple.*` (JSON-persisted, ~20 files) is intended to eventually be removed in favor of `simplescript.*` (Room-backed), but `simplescript.*` currently has no execution engine — its `ScriptCompiler` emits Lua source that nothing yet feeds to `NativeLuaScriptRunner`. Building that path and migrating/dropping legacy data is deliberately out of scope for this module split; legacy code stays in `:app`, unmoved, until that follow-up work happens.

## Status

Accepted.
