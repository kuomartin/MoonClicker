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
_Avoid_: DisplayManager, DisplayLifecycleOwner.

**GLES Distributor**:
The native OpenGL component that reads a virtual display's surface and fans it out to multiple consumers (streaming, frame capture, vision) at once.
_Avoid_: Renderer, frame broadcaster.

**Streaming Pipeline**:
The zero-copy H.264 video streaming path connecting `H264EncoderSink` via `MediaCodec` to remote consumers like `WorkbenchServer` ([ADR-0016](docs/adr/0016-h264-streaming-reprioritized.md)).
_Avoid_: Video stream, screen recorder, MJPEG stream.

**InputController**:
Injects touch, swipe, and multi-touch events into a specific virtual display via `IRelcV2Service`.
_Avoid_: Touch injector, event sender.

**Surface Space / Logical Space (Surface 空間 / 邏輯空間)**:
The two coordinate systems of a virtual display: Surface Space represents the fixed physical pixel dimensions at creation time (`AImageReader`), while Logical Space reflects WindowManager orientation used by input injection and vision matching ([ADR-0012](docs/adr/0012-surface-size-is-owned-not-derived.md)).
_Avoid_: Frame coordinates, screen coordinates (when unspecified).

**Orientation Chain (方向鏈)**:
The one-way orientation propagation sequence (`Target App → VD → FullscreenDisplayActivity → Physical Display`) that aligns activity and physical screen rotation with the virtual display.
_Avoid_: Rotation sync, two-way orientation.

**Viewport**:
A pure geometry model that maps a virtual display's logical dimensions and orientation into a target view rectangle for frame rendering and reverse touch coordinate mapping.
_Avoid_: Scale matrix, touch mapper.

**Script Engine**:
The embedded Lua runtime that drives automation as a linear program executing from top to bottom on its own thread without a tick loop ([ADR-0002](docs/adr/0002-lua-as-scripting-engine.md), [ADR-0010](docs/adr/0010-linear-scripts-replace-the-tick-loop.md), [ADR-0011](docs/adr/0011-scripts-do-not-own-displays.md)).
_Avoid_: Automation engine, macro engine, tick loop.

**Script Workbench**:
The embedded HTTP and WebSocket development server inside the ReLC app (`WorkbenchServer`, `WorkbenchService`) paired with the VS Code extension (`relc-script-workbench`) for script synchronization, remote control, display mirroring, and telemetry. Protected by [[Pairing Mode]] and [[Workbench Auth Token]].
_Avoid_: Dev server, debugger backend, sync service.

**Pairing Mode**:
A temporary 5-minute security window on [[Script Workbench]] during which a randomized 6-digit PIN is displayed in the ReLC app to authenticate new development clients.
_Avoid_: Open server, permanent PIN, discovery mode.

**Workbench Auth Token**:
A cryptographically secure random token issued by [[Script Workbench]] upon successful PIN verification, required for all HTTP and WebSocket requests.
_Avoid_: API key, session password.

**Script Folder**:
A directory under external private storage (`Android/data/com.xaxaxax.relc/files/scripts/<id>/`) containing `main.lua`, optional `script.json`, and template images.
_Avoid_: Script file, script record, script database entry.

**ScriptSession**:
The active script execution instance in `:app` that resolves a [[ScriptTarget]], runs the Script Engine, posts the status notification, and manages lifecycle without destroying the display.
_Avoid_: ScriptManager, script runner.

**ScriptTarget**:
The designated execution target for a script: the physical screen (input only), an existing virtual display, or a newly allocated virtual display.
_Avoid_: Display id, target screen.

**Engine Module**:
The `:engine` Gradle module — the sole boundary allowed to touch native internals (Script Engine, VisionMatcher, RelcV2Service).
_Avoid_: Native layer, backend module.

**EngineStateRepository**:
The observable single source of truth (`StateFlow<EngineState>`) in the Engine Module aggregating script run states and vision matching results.
_Avoid_: Engine status, native state holder.

**ScriptEngine**:
The public Kotlin facade over native `LuaNative` bindings in `:engine`, used to start/stop scripts and exchange shared data.
_Avoid_: LuaEngineControl, JNI bridge, LuaNative (when referencing public caller API).

**ScriptHost**:
The Engine Module's single JNI upcall target implementing non-native Lua APIs (`input.*`, `app.launch`, `device.*`, `data.set`) on top of `IRelcV2Service`.
_Avoid_: JNI callbacks, native bridge.

**VisionMatcher**:
The native OpenCV-backed component that performs on-demand template matching on virtual display frames in logical coordinates ([ADR-0003](docs/adr/0003-opencv-for-vision-matching.md), [ADR-0013](docs/adr/0013-templates-are-logical-space.md)).
_Avoid_: VisionEngine, image recognizer, matcher.

**RelcV2Service**:
The Shizuku-hosted AIDL service (`IRelcV2Service`) providing virtual display management, native input injection, and app launching ([ADR-0001](docs/adr/0001-v2-service-supersedes-v1.md)).
_Avoid_: Shizuku service, backend service.

**Script Status Notification**:
The persistent system notification posted during an active [[ScriptSession]] providing execution status and a stop action ([ADR-0007](docs/adr/0007-drop-lua-overlay-ui-for-status-notification.md)).
_Avoid_: HUD, overlay, floating status.
