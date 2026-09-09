# ReLC

ReLC is an Android remote Lua-control and automation tool: it runs a target app inside a virtual display, injects input into it, and drives it with a Lua script, optionally guided by on-device template matching.

## Language

**Virtual Display**:
An off-screen Android display created via Shizuku, used to run a target app in the background without occupying the physical screen.
_Avoid_: Hidden screen, background window.

**DisplaySink**:
A pluggable output endpoint attached to a virtual display's render surface, swappable at runtime (`NoOpSink`, `H264EncoderSink`, `DirectSink`) without tearing down the display.
_Avoid_: Output handler, renderer.

**VirtualDisplayController**:
Owns the lifecycle of a virtual display — creation, sink attachment/replacement, and teardown.

**GLES Distributor**:
The native OpenGL component that reads a virtual display's surface and fans it out to multiple consumers (streaming, frame capture, vision) at once.
_Avoid_: Renderer, frame broadcaster.

**Streaming Pipeline**:
The path from a virtual display's GLES output through `H264EncoderSink` and a LocalSocket to a consumer (video player, frame grabber). Currently deprioritized — see [ADR-0004](docs/adr/0004-h264-streaming-deprioritized.md).

**InputController**:
Injects touch/swipe/multi-touch events into a specific virtual display via `IRelcV2Service`.
_Avoid_: Touch injector, event sender.

**Script Engine**:
The embedded Lua runtime that drives automation — it owns virtual displays, input, and vision matching from a user-authored script. See [ADR-0002](docs/adr/0002-lua-as-scripting-engine.md).
_Avoid_: Automation engine, macro engine.

**Engine Module**:
The `:engine` Gradle module — the sole boundary allowed to touch native internals (the Script Engine, VisionEngine, RelcV2Service). Other modules observe it only through `EngineStateRepository`; they never reach into its internals directly. See [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md).
_Avoid_: Native layer, backend module.

**EngineStateRepository**:
The Engine Module's single observable source of truth — a `StateFlow<EngineState>` aggregating run state (idle/starting/running/finished/error/stopped, with error detail) and the latest match result. Fed by native events rather than polled. Does not yet cover per-virtual-display state — see [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md)'s scope note.
_Avoid_: Engine status, native state holder.

**LuaEngineControl**:
The public facade over `LuaNative` (the Engine Module's JNI bridge, `internal` to `:engine`). Other modules start/stop the engine, send UI events, and read `sharedData` through this — never through `LuaNative` directly. See [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md).
_Avoid_: The JNI bridge, LuaNative (when describing what other modules call).

**LuaUiManager**:
The bridge (in `:overlay`) that lets a running Lua script add or update Compose UI elements (HUD, controls) at runtime, registered as `LuaNative`'s `LuaUiSink` from `RelcApplication.onCreate`.

**VisionEngine**:
The native OpenCV-backed component that performs template matching against a virtual display's frames, exposed to scripts as the `match.*` API. See [ADR-0003](docs/adr/0003-opencv-for-vision-matching.md).
_Avoid_: Image recognizer, matcher.

**RelcV2Service**:
The current Shizuku-hosted service exposing virtual display, input, and launch capabilities over AIDL (`IRelcV2Service`), with native acceleration. Supersedes `RelcShizukuService` (V1), which is deprecated. See [ADR-0001](docs/adr/0001-v2-service-supersedes-v1.md).
_Avoid_: Shizuku service (ambiguous between V1/V2), backend service.

**Overlay UI**:
Floating, always-on-top Compose UI (control bar, HUD) rendered by the `:overlay` module's `ClickAssistOverlayService` (an AccessibilityService), which reaches the Engine Module only through `LuaEngineControl` — never `LuaNative` directly. Its content is pluggable per script type via `OverlayContentExtension`, so the service itself never references the legacy Simple Script editor UI that stays in `:app`. See [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md).

**OverlayContentExtension**:
The seam that lets `:overlay`'s `ClickAssistOverlayService` render script-type-specific content without depending on that type's implementation. Registered via Hilt multibinding, keyed by a plain string matching `ScriptConfig.ScriptCodeType.name` (`:overlay` doesn't depend on `ScriptConfig`). `:overlay` registers the Lua-UI renderer; `:app` registers the legacy Simple Script renderer. See [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md).
_Avoid_: Overlay renderer, content provider (ambiguous with Android's ContentProvider).
