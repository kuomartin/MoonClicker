# ReLC Architecture

## 功能目標對應關係

| 組合 | 效果 | 核心元件 |
|------|------|---------|
| 1 | 背景執行 APP | VirtualDisplay + ActivityLaunch |
| 1+2+3 | 監控模式：開 APP 並看畫面 | VirtualDisplay + H264Sink + VideoPlayerUI |
| 1+4 | 操作模式：開 APP 並觸控 | VirtualDisplay + InputController |
| 1+4+5 | 腳本模式：背景自動化 | VirtualDisplay + InputController + ScriptEngine |
| 1+2+5+6 | 視覺模式：影像辨識自動化 | VirtualDisplay + H264Sink + FrameGrabber + VisionEngine + ScriptEngine |

---

## 系統架構總覽

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           App Process (normal uid)                      │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                        UI Layer (Compose)                        │   │
│  │   SettingsScreen │ VirtualDisplayScreen │ ScriptScreen          │   │
│  └───────────────────────────┬─────────────────────────────────────┘   │
│                              │ observe StateFlow                        │
│  ┌───────────────────────────▼─────────────────────────────────────┐   │
│  │                      ViewModel Layer                             │   │
│  │   MainViewModel │ VirtualDisplayViewModel │ ScriptViewModel     │   │
│  └──────┬──────────────────────┬──────────────────┬────────────────┘   │
│         │                      │                  │                     │
│  ┌──────▼──────┐  ┌────────────▼────────┐  ┌─────▼──────────────┐    │
│  │  Shizuku    │  │ VirtualDisplay      │  │  Script Engine     │    │
│  │  Manager   │  │ Controller          │  │  (LuaJ)            │    │
│  └──────┬──────┘  └────────────┬────────┘  └─────┬──────────────┘    │
│         │                      │                  │                     │
│         │              ┌───────▼──────────────────▼──────────┐        │
│         │              │           DisplaySink (可插拔)        │        │
│         │              │  NoOpSink │ H264Sink │ DirectSink    │        │
│         │              └───────┬──────────────────────────────┘        │
│         │                      │                                        │
│         │              ┌───────▼──────────────────────────────┐        │
│         │              │         Stream Pipeline               │        │
│         │              │  H264Encoder → LocalSocketServer     │        │
│         │              └───────────────┬──────────────────────┘        │
│         │                              │ UNIX socket                    │
│         │              ┌───────────────▼──────────────────────┐        │
│         │              │         Stream Consumer               │        │
│         │              │  VideoPlayer │ FrameGrabber          │        │
│         │              │                    │                  │        │
│         │              │             VisionEngine              │        │
│         │              └──────────────────────────────────────┘        │
└─────────│────────────────────────────────────────────────────────────┘
          │ AIDL Binder (Shizuku IPC)
┌─────────▼──────────────────────────────────────────────────────────────┐
│                  Shizuku User Service (shell uid)                       │
│                                                                         │
│  IRelcShizukuService.Stub                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐  │
│  │  DisplayManager │  │  InputManager   │  │  ActivityTaskManager │  │
│  │  .createVirtual │  │  .injectInput   │  │  .startActivity      │  │
│  │  Display()      │  │  Event()        │  │  (in display)        │  │
│  └─────────────────┘  └─────────────────┘  └──────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Package 結構（單 Gradle Module）

目前保持單 module 架構，依 package 做邏輯隔離：

```
com.xaxaxax.relc/
│
├── core/
│   ├── DisplayConfig.kt          # data class (name, width, height, dpi)
│   ├── StreamConfig.kt           # data class (codec, bitrate, fps)
│   └── DisplayState.kt           # sealed class 狀態機
│
├── shizuku/
│   ├── ShizukuManager.kt         # ✅ 已有
│   ├── ShizukuUserService.kt     # ✅ 已有
│   ├── InputManager.kt           # ✅ 已有（需擴充 displayId）
│   └── DisplayManagerBridge.kt   # NEW: 封裝 Shizuku createVirtualDisplay
│
├── display/
│   ├── VirtualDisplayController.kt  # 生命週期管理，組合 Shizuku + Sink
│   ├── DisplaySink.kt               # interface: 可插拔輸出端
│   ├── NoOpSink.kt                  # 純背景執行（無捕捉）
│   ├── H264Sink.kt                  # H.264 + LocalSocket
│   └── DirectSurfaceSink.kt         # 直接輸出至 Surface（in-process）
│
├── stream/
│   ├── encoder/
│   │   └── H264Encoder.kt        # MediaCodec H.264 encoder，提供 inputSurface
│   ├── transport/
│   │   ├── StreamProtocol.kt     # Packet framing 定義
│   │   ├── LocalSocketServer.kt  # 寫端（encoder → socket）
│   │   └── LocalSocketClient.kt  # 讀端（socket → consumer）
│   └── decoder/
│       └── H264Decoder.kt        # MediaCodec H.264 decoder → Surface
│
├── input/
│   └── InputController.kt        # 封裝 InputManager，支援 displayId & 多點觸控
│
├── script/
│   ├── ScriptEngine.kt           # LuaJ 執行環境
│   ├── ScriptApi.kt              # 暴露給 Lua 的 API (display/input/vision/sleep)
│   └── ScriptRunner.kt           # Coroutine 包裝，執行 & 停止腳本
│
├── vision/
│   ├── FrameGrabber.kt           # 從 LocalSocket stream 截取 Bitmap
│   └── VisionEngine.kt           # 模板比對 / OCR
│
└── ui/
    ├── theme/                    # ✅ 已有
    ├── MainActivity.kt           # ✅ 已有
    ├── MainViewModel.kt          # ✅ 已有（逐步重構）
    └── screen/
        ├── SettingsScreen.kt     # 從 MainActivity 提取
        ├── VirtualDisplayScreen.kt
        └── ScriptScreen.kt
```

---

## 核心元件設計

### 1. AIDL 擴充 `IRelcShizukuService`

```aidl
// IRelcShizukuService.aidl
interface IRelcShizukuService {
    // ✅ 已有
    boolean setOverlayAllowed(String packageName);
    boolean grantRuntimePermission(String packageName, String permissionName);

    // VirtualDisplay 管理
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface);
    boolean setVirtualDisplaySurface(int displayId, in Surface surface);  // 熱插拔 sink
    boolean destroyVirtualDisplay(int displayId);

    // 在指定 Display 中啟動 App
    boolean launchInDisplay(String packageName, int displayId);

    // Input 注入（displayId 已設在 MotionEvent 上）
    boolean injectMotionEvent(in MotionEvent event);
    boolean injectKeyEvent(in KeyEvent event);
}
```

### 2. DisplaySink — 可插拔輸出端

```
VirtualDisplay 的 Surface 來源由 DisplaySink 提供，
可在運行時透過 setVirtualDisplaySurface() 熱替換：

NoOpSink      → surface = null  （純背景，不捕捉畫面）
H264Sink      → surface = MediaCodec inputSurface
DirectSink    → surface = 外部傳入的 SurfaceView surface
```

```kotlin
// display/DisplaySink.kt
interface DisplaySink {
    fun acquireSurface(): Surface?   // null = 不需要畫面捕捉
    fun start()
    fun stop()
    fun release()
}
```

### 3. Streaming Pipeline (H264Sink 路徑)

```
VirtualDisplay
    │  (renders to)
    ▼
MediaCodec inputSurface       ← H264Encoder.inputSurface
    │  (H.264 NAL units)
    ▼
H264Encoder.outputBuffer
    │
    ▼
LocalSocketServer              socket name: "relc_vd_{displayId}"
    │  (framed packets, see protocol below)
    ▼
LocalSocketClient
    │
    ├──► H264Decoder → SurfaceView    (VideoPlayerScreen)
    │
    └──► FrameGrabber → Bitmap        (VisionEngine / ScriptEngine)
```

### 4. Stream Protocol 封包格式

```
每個封包：
┌──────────────┬────────┬────────────┬──────────────┬─────────────────┐
│  Magic (4B)  │Type(1B)│Timestamp(8B)│DataLen (4B) │  Data (N bytes) │
│  0x52454C43  │        │   ms        │             │                 │
│  "RELC"      │        │             │             │                 │
└──────────────┴────────┴────────────┴──────────────┴─────────────────┘

Type:
  0x01 = CONFIG  → JSON: { width, height, codec:"h264", fps, bitrate }
  0x02 = VIDEO   → H.264 access unit (SPS/PPS/IDR/P-frame)
  0x03 = PING    → keepalive
```

> 第一個封包固定為 CONFIG，之後全部是 VIDEO。

### 5. VirtualDisplayController 生命週期

```
         create(config, sink)
IDLE ──────────────────────────► CREATED
                                     │
                           replaceSink(newSink)  ← 熱插拔（不用重建 VD）
                                     │
                                destroy()
                                     │
                                     ▼
                                DESTROYED
```

```kotlin
// display/VirtualDisplayController.kt
class VirtualDisplayController(private val service: IRelcShizukuService) {
    var displayId: Int = Display.INVALID_DISPLAY
    private var sink: DisplaySink = NoOpSink()

    suspend fun create(config: DisplayConfig, sink: DisplaySink = NoOpSink()) {
        this.sink = sink
        displayId = service.createVirtualDisplay(
            config.name, config.width, config.height, config.densityDpi,
            sink.acquireSurface()
        )
        sink.start()
    }

    fun replaceSink(newSink: DisplaySink) {
        sink.stop()
        sink = newSink
        service.setVirtualDisplaySurface(displayId, newSink.acquireSurface())
        newSink.start()
    }

    fun destroy() {
        sink.stop(); sink.release()
        service.destroyVirtualDisplay(displayId)
        displayId = Display.INVALID_DISPLAY
    }
}
```

### 6. Script Engine (LuaJ)

**依賴**：`org.luaj:luaj-jse:3.0.1`

Lua 可用 API：

```lua
-- 建立虛擬顯示器
local id = display.create(1080, 1920)

-- 在虛擬顯示器啟動 APP
display.launch("com.example.app", id)

-- 輸入操作（displayId 為 Lua 全域，由腳本指派）
displayId = id
input.tap(50, 540, 960)                      -- (durationMs, x, y)
input.swipe(500, 540, 1500, 540, 500)        -- durationMs, x1, y1, ...（奇數個參數；L2 弧長）
input.swipeL1(500, 540, 1500, 540, 500)      -- 同上，L1（曼哈頓）弧長
input.key(66, id)   -- keyCode 66 = ENTER

-- 畫面截圖（返回 Bitmap userdata）
local bmp = screen.capture(id)

-- 視覺辨識
local pos = vision.find(bmp, "template.png")   -- 模板比對，返回 {x, y} 或 nil
local text = vision.ocr(bmp, {x=0,y=0,w=200,h=100})  -- 指定區域 OCR

-- 工具
sleep(1000)   -- ms
log("message")
```

### 7. Vision Engine

| 功能 | 實作方案 | APK 影響 |
|------|---------|---------|
| 模板比對 (findTemplate) | OpenCV Android SDK | +20MB |
| OCR | ML Kit Text Recognition | 動態下載 |
| 物件偵測 | TFLite (選配) | 依模型大小 |

FrameGrabber 透過 LocalSocket 接收 H.264 stream，使用 `ImageReader` 或自訂 `SurfaceTexture → Bitmap` pipeline 提供幀給 VisionEngine。

---

## 各組合的初始化流程

### 組合 1+4+5：腳本自動化

```
App 啟動
  └─► ShizukuManager.connect()
  └─► ShizukuUserService.connect<IRelcShizukuService>()
  └─► VirtualDisplayController.create(config, sink=NoOpSink)
  └─► service.launchInDisplay("target.app", displayId)
  └─► ScriptRunner.run("script.lua")
        └─► displayId = … ; input.tap(durationMs, x, y)   ← InputController → Shizuku
```

### 組合 1+2+5+6：視覺自動化

```
App 啟動
  └─► 同上建立 VirtualDisplay，sink=H264Sink
  └─► LocalSocketClient 連接
  └─► FrameGrabber 訂閱 frames
  └─► ScriptRunner.run("vision_script.lua")
        └─► local bmp = screen.capture(id)
        └─► local pos = vision.find(bmp, "button.png")
        └─► input.tap(50, pos.x, pos.y)   -- 事先設定全域 displayId
```

---

## 依賴清單（待加入 build.gradle.kts）

```kotlin
// Lua 腳本引擎
implementation("org.luaj:luaj-jse:3.0.1")

// 視覺：OpenCV
implementation("org.opencv:opencv:4.9.0")

// 視覺：ML Kit OCR（選配）
implementation("com.google.mlkit:text-recognition:16.0.1")

// Coroutines（應已有）
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

---

## 實作順序建議

| 階段 | 工作 | 對應功能 |
|------|------|---------|
| **P1** | 擴充 AIDL + 實作 `createVirtualDisplay` / `launchInDisplay` | 功能 1 |
| **P1** | 修復 `InputManager` displayId（down/move 都要設） | 功能 4 |
| **P2** | `H264Encoder` + `LocalSocketServer/Client` + `H264Decoder` | 功能 2+3 |
| **P2** | `VirtualDisplayController` + `DisplaySink` 介面 + `NoOpSink` / `H264Sink` | 串接 |
| **P2** | `VideoPlayerScreen`（Compose + SurfaceView） | 功能 3 UI |
| **P3** | `ScriptEngine` (LuaJ) + `ScriptApi` + `ScriptRunner` | 功能 5 |
| **P4** | `FrameGrabber` + `VisionEngine` (OpenCV 模板比對) | 功能 6 |
| **P4** | ML Kit OCR 整合 | 功能 6 擴充 |
