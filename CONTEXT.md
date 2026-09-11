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

**Surface 空間 / 邏輯空間**:
同一個虛擬顯示的兩個座標系。**Surface 空間**是它建立時的尺寸，影格與 `AImageReader` 都在這裡，旋轉時尺寸不變、內容被轉「進」其中。**邏輯空間**是 WindowManager 眼中的顯示，旋轉 90/270 時長寬互換，`injectMotionEvent` 與 `match.*` 的對外座標都以它為準。兩者在未旋轉時恆等，旋轉時差一個直角——混用即為錯位的來源。
_Avoid_: 影格座標／畫面座標（沒有指明是哪一個）。

**方向鏈**:
`Y → VD → X → MainDisplay` 的單向傳遞：虛擬顯示裡的 app 或感測器決定虛擬顯示的方向，`FullscreenDisplayActivity` 跟隨虛擬顯示，實體螢幕再跟隨它。四環中兩環由系統提供（WindowManager 對 app 宣告方向的仲裁、實體螢幕跟隨前景 activity）。鏈失效時（API 27–28、sw ≥ 600dp、使用者關閉自動旋轉）畫面退回 [[Viewport]] 的幾何層，仍然正確、只是不填滿。
_Avoid_: 旋轉同步（暗示雙向）。

**Viewport**:
鏡像一個虛擬顯示時的幾何：它當前的邏輯尺寸與方向，投影到 view 的哪個矩形。同一個 instance 同時服務畫面呈現（內容矩形、反向旋轉角、未旋轉的佈局框）與觸控反向映射（view 座標 → 邏輯座標），因此兩側不可能算出不一致的幾何。純資料，不依賴 `android.graphics`，可在純 JVM 測試中窮舉「旋轉 × 長寬比 × letterbox」的組合。
_Avoid_: 縮放矩陣、觸控映射（兩者都只講了它的一半）。

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
The public facade over `LuaNative` (the Engine Module's JNI bridge, `internal` to `:engine`). Other modules start/stop the engine and read `sharedData` (what a script publishes via the Lua `app.set_data` API) through this — never through `LuaNative` directly. See [ADR-0006](docs/adr/0006-module-split-and-engine-facade.md).
_Avoid_: The JNI bridge, LuaNative (when describing what other modules call).

**VisionEngine**:
The native OpenCV-backed component that performs template matching against a virtual display's frames, exposed to scripts as the `match.*` API. See [ADR-0003](docs/adr/0003-opencv-for-vision-matching.md).
_Avoid_: Image recognizer, matcher.

**RelcV2Service**:
The current Shizuku-hosted service exposing virtual display, input, and launch capabilities over AIDL (`IRelcV2Service`), with native acceleration. Supersedes `RelcShizukuService` (V1), which is deprecated. See [ADR-0001](docs/adr/0001-v2-service-supersedes-v1.md).
_Avoid_: Shizuku service (ambiguous between V1/V2), backend service.

**Script Status Notification**:
The persistent system notification that shows which scripts are currently running, posted by `ScriptStatusNotifier` from `ScriptManager.scriptStates` and cancelled when nothing is running. Its body tap and 「查看狀態」 action open the Scripts page; 「停止所有」 stops every running script. It replaced the removed Overlay UI and needs no window permission. See [ADR-0007](docs/adr/0007-drop-lua-overlay-ui-for-status-notification.md).
_Avoid_: HUD, overlay, floating status.
