# scrcpy 怎麼處理旋轉

研究問題：MoonClicker 目前用「感測器 → Shizuku → `freezeDisplayRotation(VD)` → `onDisplayChanged` →
`activity.requestedOrientation`」這條四環鏈做自動旋轉（`OrientationChain.kt`），
轉場會卡。scrcpy 面對等價的問題是怎麼解的？哪些手法搬得過來、哪些搬不過來？
「乾脆拿掉自動旋轉」這個選項，在 scrcpy 的設計裡有沒有對應？

研究日期 2026-09-14。

**方法**：全部讀 scrcpy 上游原始碼，
`https://github.com/Genymobile/scrcpy`，commit
`19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（`v4.1-1-g19c1261`，2026-09-05）。
讀的範圍是 `server/src/main/java/com/genymobile/scrcpy/**`、`app/src/**`（C client）、
`doc/*.md`、`FAQ.md`、`README.md`，以及 `git log` 的 commit message。
下文引用格式為 `路徑:行號`（行號對應上述 commit），並一律附上符號名——行號會漂，符號名不會。
引用 commit 時用短 SHA。

**沒有驗證的部分**：沒有跑過 scrcpy，沒有量過它的旋轉轉場延遲，
也沒有在真機上比對 MoonClicker 與 scrcpy 的表現。本文只描述 scrcpy 的**設計**，
不構成「照抄就會順」的證據。MoonClicker 卡頓的根因沒有在本文中被測量或證實。

---

## TL;DR

1. **scrcpy 的旋轉變換不在「顯示端」，在 capture 端。** 預設路徑下它連變換都沒有：
   VD 直接以「當下已經交換過長寬的 logical display 尺寸」重建，framework 的鏡像投影
   就把內容擺正了（`ScreenCapture.prepare()` / `start()`）。只有在使用者指定
   `--capture-orientation` / `--crop` / `--angle`，或走 `--new-display` 時，
   才插一段 OpenGL 仿射變換到 encoder input surface 之前（`AffineOpenGLFilter` + `OpenGLRunner`）。
2. **旋轉事件的來源是 `DisplayManager.DisplayListener`（API < 34）或
   `IDisplayWindowListener`（API ≥ 34），不是 `IRotationWatcher`** ——
   後者已於 `eac711ac` 明確刪除。
3. **旋轉沒有任何 debounce。** 收到事件、比對 `DisplayProperties(size, rotation)`
   不同就立刻 reset。唯一的 300 ms debounce 是 flex display 的「視窗拖曳改 VD 尺寸」，
   與旋轉無關。避免抖動靠的是**結構**（每個 session 開始時先把自己造成的新狀態寫進
   monitor，於是自己造成的那次 `onDisplayChanged` 比對相等、不觸發 reset），不是靠等待。
4. **旋轉 = 整條 pipeline 拆掉重建。** `MediaCodec` 送 EOS → `encode()` 收尾 →
   `mediaCodec.stop()/reset()` → 用新的寬高重新 `configure()` → 新的 input surface →
   VD 重建（鏡像模式）或 `setSurface()`（新顯示模式）→ `mediaCodec.start()` →
   送一個 12-byte **session packet** 宣告新尺寸 → 新的 codec config（SPS/PPS）→ key frame。
   client 端不知道「旋轉」這件事，它只看到影格尺寸換了，然後照比例調整視窗。
5. **scrcpy 從不把手機的方向感測器接到任何 display 的 rotation 上。**
   它唯一呼叫 `freezeDisplayRotation` 的地方是使用者按 <kbd>MOD</kbd>+<kbd>r</kbd>
   的那條手動指令（`Device.rotateDevice`）。`--new-display` 造出來的 VD 會不會轉，
   完全交給 WindowManager 依 app 宣告的方向仲裁。
   **也就是說：scrcpy 的預設設計，就等於「沒有自動旋轉」。**
6. **對付「用舊 rotation 算出來的點擊」的手法是版本戳記 + 丟棄**：
   client 每一筆座標事件都附上它產生時的影格尺寸，server 端
   `PositionMapper.map()` 一比對不合就回 `null`，整個事件丟掉。

---

## Q1. 旋轉變換放在哪一層

### 三個層級，文件講得很明白

`doc/video.md:117-127`（`## Orientation`）：

> The orientation may be applied at 3 different levels:
>  - The [shortcut](shortcuts.md) <kbd>MOD</kbd>+<kbd>r</kbd> requests the
>    device to switch between portrait and landscape (the current running app may
>    refuse, if it does not support the requested orientation).
>  - `--capture-orientation` changes the mirroring orientation (the orientation
>    of the video sent from the device to the computer). This affects the
>    recording.
>  - `--orientation` is applied on the client side, and affects display and
>    recording. For the display, it can be changed dynamically using
>    [shortcuts](shortcuts.md).

對應關係：

| 選項 | 作用階段 | 實作位置 |
| --- | --- | --- |
| <kbd>MOD</kbd>+<kbd>r</kbd> | 裝置端真的旋轉那個 display | `Device.rotateDevice`（`server/.../device/Device.java:200-218`） |
| `--capture-orientation` | encoder input surface 之前的 OpenGL 變換 | `VideoFilter` → `AffineOpenGLFilter` → `OpenGLRunner.start()` |
| `--display-orientation` | client 端 SDL 算繪時的旋轉 | `sc_screen_render`（`app/src/screen.c:269-301`） |
| `--record-orientation` | 只寫一個 display matrix 到 MP4/MKV，不動影像資料 | `sc_recorder_set_orientation`（`app/src/recorder.c:508-541`） |
| `--orientation` | 同時等於前兩者 | `app/src/cli.c:710-716`（`OPT_ORIENTATION`） |

`--record-orientation` 特別值得注意：它**完全不碰像素**，只是
`av_display_rotation_set(matrix, angle)` 寫進 `AV_PKT_DATA_DISPLAYMATRIX`
side data（`app/src/recorder.c:534-538`）——最便宜的旋轉就是不旋轉，只標註。

### 預設路徑：沒有變換

`ScreenCapture.prepare()`（`server/.../video/ScreenCapture.java:72-106`）：

```java
Size displaySize = displayInfo.getSize();
int displayRotation = displayInfo.getRotation();
...
VideoFilter filter = new VideoFilter(displaySize);
if (crop != null) { ... }
boolean locked = captureOrientationLock != Orientation.Lock.Unlocked;
filter.addOrientation(displayRotation, locked, captureOrientation);
filter.addAngle(angle);
transform = filter.getInverseTransform();
videoSize = filter.getOutputSize().constrain(videoConstraints);
```

預設 `captureOrientationLock == Unlocked`、`captureOrientation == Orient0`、
`crop == null`、`angle == 0`。走進
`VideoFilter.addOrientation(int, boolean, Orientation)`（`VideoFilter.java:90-97`）：
`locked` 為 false 所以不做反向旋轉；接著
`addOrientation(Orient0)`（`:82-88`）算出 `ccwRotation = 0`，
`addRotation(0)` 直接 return（`:71-74`）。**`transform` 全程是 `null`。**

`ScreenCapture.start()`（`:109-130`）於是走 else 分支：

```java
if (transform != null) {
    // If there is a filter, it must receive the full display content
    inputSize = displayInfo.getSize();
    OpenGLFilter glFilter = new AffineOpenGLFilter(transform);
    glRunner = new OpenGLRunner(glFilter);
    surface = glRunner.start(inputSize, videoSize, surface);
} else {
    // If there is no filter, the display must be rendered at target video size directly
    inputSize = videoSize;
}
```

然後 `ServiceManager.getDisplayManager().createVirtualDisplay("scrcpy",
inputSize.getWidth(), inputSize.getHeight(), displayId, surface)`（`:133-134`），
呼叫的是隱藏的靜態鏡像 API
`DisplayManager.createVirtualDisplay(String, int, int, int displayIdToMirror, Surface)`
（`wrappers/DisplayManager.java:164-174`）——**不帶任何 flag**。

也就是說：**鏡像模式下「旋轉」的實作就是「用新的（已交換）寬高重建一個鏡像 VD」。**
`displayInfo.getSize()` 在 display 0 轉到橫向後已經是 2400×1080，
VD 就照 2400×1080 建，framework 的鏡像投影負責把內容擺正。零變換、零成本。

### 例外：`--new-display` 必須自己旋轉

`NewDisplayCapture.prepare()`（`server/.../video/NewDisplayCapture.java:189-208`）：

```java
// DisplayInfo gives the oriented size (so videoSize includes the display rotation)
videoSize = filter.getOutputSize();

// However, the virtual display video always remains in its original orientation, so it must be rotated manually.
// This additional display rotation must not be included in the input events transform (the expected coordinates are already in the
// physical display size)
if ((displayRotation % 2) == 0) {
    physicalSize = displaySize;
} else {
    physicalSize = displaySize.rotate();
}
VideoFilter displayFilter = new VideoFilter(physicalSize);
displayFilter.addRotation(displayRotation);
AffineMatrix displayRotationMatrix = displayFilter.getInverseTransform();
displayTransform = AffineMatrix.multiplyAll(displayRotationMatrix, eventTransform);
```

**這段註解是本文對 MoonClicker 最直接的一條情報。** 一個自己建立的 VD（帶
`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`，見 `:213-217`）旋轉之後，
它的**輸出 surface 仍然維持建立時的尺寸與方向**，內容是被「轉進」那塊固定尺寸的
surface 裡；要在畫面上看到正的，必須自己再轉一次。scrcpy 用一段
OpenGL pass 做（`start()`，`:267-273`：`glRunner.start(physicalSize, videoSize, surface)`）。

注意這裡刻意分成兩個矩陣：`eventTransform` 給輸入座標反解用，
`displayTransform = displayRotationMatrix × eventTransform` 給畫面用。
**顯示多轉的那一層，不能算進輸入座標的反解**，因為注入事件用的座標系是 VD 的邏輯座標。

---

## Q2. 怎麼觀察旋轉，怎麼防抖

### 監聽方式：`DisplayListener` 或 `IDisplayWindowListener`，不是 `IRotationWatcher`

`DisplayMonitor`（`server/.../display/DisplayMonitor.java`）。分岔條件在 `:26`：

```java
// On Android 14, DisplayListener may be broken (it never sends events). This is fixed in recent Android 14 upgrades, but we can't really
// detect it directly, so register a DisplayWindowListener (introduced in Android 11) to listen to configuration changes instead.
// It has been broken again after an Android 15 upgrade: <https://github.com/Genymobile/scrcpy/issues/5908>
// So use the default method only before Android 14.
private static final boolean USE_DEFAULT_METHOD = Build.VERSION.SDK_INT < AndroidVersions.API_34_ANDROID_14;
```

- `SDK_INT < 34`：`ServiceManager.getDisplayManager().registerDisplayListener(...)`
  跑在自己的 `HandlerThread("DisplayListener")` 上（`:47-64`）。
- `SDK_INT >= 34`：`IWindowManager.registerDisplayWindowListener(IDisplayWindowListener)`，
  用 `onDisplayConfigurationChanged(displayId, Configuration)`（`:66-83`，
  wrapper 在 `wrappers/WindowManager.java:194-211`）。

`IRotationWatcher` / `IDisplayFoldListener` **已經被刪掉**。commit `eac711ac`
（"Remove unused rotation and fold listeners"）：

> IRotationWatcher and IDisplayFoldListener are no longer used since
> commit 39d51ff2cc2f3e201ad433d48372b548e5dd11d3.

更早的 `e26bdb07`（"Listen to display changed events"）說明了理由：

> Replace RotationWatcher and DisplayFoldListener by a single
> DisplayListener, which is notified whenever the display size or dpi
> changes.

**這是有意的收斂**：一個 listener 同時覆蓋旋轉、折疊、尺寸、dpi 四種變化，
而不是三個來源各管一段。

### 防抖：沒有時間性的防抖，只有狀態比對

`DisplayMonitor.checkDisplayPropertiesChanged()`（`:119-143`）：

```java
DisplayProperties newProps = new DisplayProperties(di.getSize(), di.getRotation());
DisplayProperties oldProps = getAndSetDisplayProperties(newProps); // exchange with synchronization
if (!newProps.equals(oldProps)) {
    // Reset only if the properties are different
    listener.onDisplayPropertiesChanged(newProps);
}
```

關鍵配套是 `setSessionDisplayProperties(DisplayProperties)`（`:115-117`），
在每個 capture session 的 `prepare()` 裡被呼叫：

- `ScreenCapture.prepare()`，`ScreenCapture.java:85`
- `NewDisplayCapture.prepare()`，`NewDisplayCapture.java:154-155`
  （註解逐字：`// Set the current display properties to avoid an unnecessary capture reset`）

**把「這一輪 session 是照哪個狀態建的」先寫進 monitor，於是自己造成的那次
`onDisplayChanged` 會比對相等而被吞掉。** 這是純結構的防迴授，沒有任何 timer。

`rotation` 被納入比較是 2026-04 才補的。commit `03878083`
（"Reset capture on rotation (fix square displays)"）：

> `DisplayMonitor` previously only triggered a capture reset when the
> display size changed. In most cases, rotation also changes dimensions,
> so the behavior was correct… except for square displays where width and
> height remain unchanged.
> However, rotation still requires a capture reset even when dimensions do
> not change, to ensure the orientation filter is applied so virtual
> displays are rendered correctly.

### 唯一的 debounce 與旋轉無關

`DisplayResizeDebouncer`（`server/.../display/DisplayResizeDebouncer.java`）：

```java
private static final long DEBOUNCE_DELAY_MS = 300;   // :10
```

它只被 `--flex-display` 用（`NewDisplayCapture.init()`，`:104-106`；
`requestResize()`，`:329-342`），目的是「使用者拖曳視窗邊框時不要每個像素都去
`virtualDisplay.resize()`」。**旋轉走不到這裡。**

另一個時間常數 `DisplayPropertiesTracker.PENDING_CACHE_DURATION = 3000`
（`DisplayPropertiesTracker.java:10`）也不是防抖：它是「把回傳的 display 變化事件
歸因回是不是自己 3 秒內送出的那次 resize 請求」的對帳表
（`onChanged`，`:35-44`；`NewDisplayCapture.java:244-259`），
歸因成功就把 reset 原因標成 `RESET_REASON_CLIENT_RESIZED`，
讓 client 知道這次尺寸變化是它自己要求的、不要再跟著調視窗。

**結論**：旋轉事件一到就動手，不等穩定。

---

## Q3. 旋轉事件發生時到底做了什麼

一句話版本在 `doc/develop.md:172`：

> On device rotation (or folding), the encoding session is reset and restarted.

### Reset 的傳遞

`ScreenCapture.init()`（`ScreenCapture.java:66-69`）：

```java
displayMonitor.start(displayId, (props) -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
```

`CaptureControl.reset(int)`（`video/CaptureControl.java:27-37`）：

```java
public synchronized void reset(int reason) {
    reset |= reason;
    if (runningMediaCodec != null) {
        try {
            runningMediaCodec.signalEndOfInputStream();
        } catch (IllegalStateException e) { /* ignore */ }
    }
}
```

**中斷正在編碼的那個 `MediaCodec` 的手段是送一個 EOS，不是直接砍執行緒。**
`SurfaceEncoder.encode()`（`SurfaceEncoder.java:252-278`）的迴圈條件是
`while (!eos)`，收到 `BUFFER_FLAG_END_OF_STREAM` 才返回。
在那之前 encoder 裡的**所有 in-flight 影格照常被 dequeue、照常
`streamer.writePacket()` 送出去**——舊方向的最後幾格不會被丟掉，
是一個乾淨的切點，不是硬截斷。

### 重建的完整順序

`SurfaceEncoder.streamCapture()` 的主迴圈（`SurfaceEncoder.java:106-184`）：

```java
do {
    int resetReasons = captureControl.consumeReset();
    if ((resetReasons & CaptureControl.RESET_REASON_TERMINATED) != 0) break;
    ...
    capture.prepare();                                   // 重新讀 DisplayInfo、重算 transform 與 videoSize
    Size size = capture.getSize();
    format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
    format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());
    ...
    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    surface = mediaCodec.createInputSurface();           // 全新的 input surface
    capture.start(surface);                              // VD 重建 / setSurface
    mediaCodec.start();
    captureControl.setRunningMediaCodec(mediaCodec);
    ...
    streamer.writeSessionMeta(size.getWidth(), size.getHeight(), isClientResize);
    encode(mediaCodec, streamer);
} while (alive);
```

`finally` 區塊（`:167-183`）：`capture.stop()`（釋放 `OpenGLRunner`）、
`mediaCodec.stop()`、`mediaCodec.reset()`、`surface.release()`。

`ScreenCapture.start()` 的頭四行（`:110-117`）先把上一輪的 display 銷毀：

```java
if (display != null) { SurfaceControl.destroyDisplay(display); display = null; }
if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
```

鏡像模式**每一次旋轉都重建 VirtualDisplay**。commit `7e3b9359`
（"Recreate the display on rotation"）給的理由是實務性的：

> On Android 14 (Pixel 8), a device rotation while the camera app was
> running resulted in an incorrect capture.
> Destroying and recreating the display fixes the issue.

`--new-display` 模式則**不重建 VD**，只換 surface
（`NewDisplayCapture.start()`，`:275-279`：`virtualDisplay.setSurface(surface)`）
——那個 VD 是 scrcpy 自己的工作區，重建會把裡面的 app 弄掉。

### Codec config 與 key frame

`mediaCodec.reset()` + 重新 `configure()` + `start()` ⇒ encoder 一定會先吐一個
`BUFFER_FLAG_CODEC_CONFIG`（新的 SPS/PPS，寬高已改）再吐 key frame。
`Streamer.writePacket(ByteBuffer, BufferInfo)`（`Streamer.java:84-89`）
把 config / keyFrame 兩個旗標寫進 12-byte frame header（`:107-124`）。

client 端 H.26x 會把 config packet 併進下一個 media packet
（`app/src/demuxer.c:286-289`，`must_merge_config_packet` + `sc_packet_merger_merge`）。
錄影端則直接丟掉後續 config packet（`app/src/recorder.c:354-360`）：

```c
// Ignore further config packets (e.g. on device orientation
// change). The next non-config packet will have the config packet
// data prepended.
```

### Session packet：唯一告訴 client「換了一輪」的訊號

`Streamer.writeSessionMeta(int width, int height, boolean isClientResize)`
（`Streamer.java:91-105`）送一個 12-byte、MSB = 1 的 header，內含新的寬高。
協定圖在 `doc/develop.md:368-383`：

> For the _video_ stream, a _session packet_ (12 bytes) is sent for each capture
> session (a session changes when the device rotates)

client：`sc_demuxer_is_session` / `sc_demuxer_parse_session`
（`app/src/demuxer.c:127-137`），主迴圈收到就
`sc_packet_source_sinks_push_session()`（`:308-312`）。
decoder 記下新的期待尺寸（`app/src/decoder.c:113-116`），
`sc_screen_apply_frame()` 看到影格尺寸變了就重算 content size 並按比例調整視窗
（`app/src/screen.c:916-949`，`resize_for_content` 在 `:841-858`）。

**整條鏈上「旋轉」這個概念只存在於 server。** `doc/develop.md:24-26`：

> The client is not aware of the device rotation (which is handled by the server), it
> just knows the dimensions of the video frames it receives.

---

## Q4. `--new-display` 模式：那個 VD 會不會轉

### 會，但驅動源是 app，不是感測器

`NewDisplayCapture.startNew()`（`NewDisplayCapture.java:213-233`）建立 VD 時帶的 flag：

```java
int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
        | VIRTUAL_DISPLAY_FLAG_PRESENTATION
        | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
```

API 33+ 再加 `TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED`，
API 34+ 再加 `OWN_FOCUS | DEVICE_DISPLAY_GROUP`（`:224-233`）。
（與 MoonClicker 的 `MoonClickerShizukuService.kt` 幾乎同一組。）

帶了 `ROTATES_WITH_CONTENT`，所以 WindowManager 會為 app 宣告的方向旋轉這個 VD；
`prepare()` 每次都重讀 `displayInfo.getRotation()`（`:157-160`）並據此算
`displayTransform`。commit `9b03bfc3`（"Handle virtual display rotation"）：

> Listen to display size changes and rotate the virtual display accordingly.

### scrcpy 不會主動把 VD 轉到某個方向（除非使用者按鍵）

全樹搜尋 `freezeDisplayRotation` / `thawDisplayRotation` / `setFixedToUserRotation` /
`setIgnoreOrientationRequest` / `watchRotation`，**呼叫點只有一個**：
`Device.rotateDevice(int displayId)`（`device/Device.java:197-218`）。

```java
/**
 * Disable auto-rotation (if enabled), set the screen rotation and re-enable auto-rotation (if it was enabled).
 */
public static void rotateDevice(int displayId) {
    WindowManager wm = ServiceManager.getWindowManager();
    boolean accelerometerRotation = !wm.isRotationFrozen(displayId);
    int currentRotation = getCurrentRotation(displayId);
    int newRotation = (currentRotation & 1) ^ 1; // 0->1, 1->0, 2->1, 3->0
    ...
    wm.freezeRotation(displayId, newRotation);
    // restore auto-rotate if necessary
    if (accelerometerRotation) {
        wm.thawRotation(displayId);
    }
}
```

`WindowManager.freezeRotation(int, int)`（`wrappers/WindowManager.java:129-150`）
就是 `IWindowManager.freezeDisplayRotation`，三個簽章版本反射分岔
（`:60-80`，含 Android 15 多出的 `String caller`，傳 `"scrcpy#freezeRotation"`）。

唯一的觸發路徑：<kbd>MOD</kbd>+<kbd>r</kbd> → `rotate_device()`
（`app/src/input_manager.c:271-276`，由 `:636` 的按鍵分派呼叫）
→ `SC_CONTROL_MSG_TYPE_ROTATE_DEVICE` → `Controller` 的
`case ControlMessage.TYPE_ROTATE_DEVICE`（`control/Controller.java:392-397`）。

`--new-display` 模式下這個 shortcut 作用在**那個虛擬顯示**上，
因為 `getActionDisplayId()`（`Controller.java:750-763`）：

```java
if (displayId != Device.DISPLAY_ID_NONE) {
    // Real screen mirrored, use the source display id
    return displayId;
}
// Virtual display created by --new-display, use the virtualDisplayId
DisplayData data = displayData.get();
...
return data.virtualDisplayId;
```

注意 `rotateDevice` 是一個 **toggle**（`(currentRotation & 1) ^ 1`），
而且立刻 `thawRotation` 把鎖放掉——它是「請系統換個方向」，不是「把方向釘死」。
`doc/video.md:120-122` 也是這樣描述的：

> requests the device to switch between portrait and landscape (the current running app may
> refuse, if it does not support the requested orientation)

**沒有任何地方讀感測器。** scrcpy server 不註冊 `SensorManager`、不做角度量化，
也沒有任何「電腦端視窗方向 → 裝置端 display rotation」的回授。

---

## Q5. 旋轉下的座標映射與「過期旋轉」

### 反向映射：同一個 `VideoFilter` 產出正反兩份矩陣

`VideoFilter.getInverseTransform()`（`VideoFilter.java:26-44`），javadoc 逐字：

> The direct affine transform describes how the input image is transformed.
> It is often useful to retrieve the inverse transform instead:
>  - The OpenGL filter expects the matrix to transform the image _coordinates_, which is the inverse transform;
>  - The click positions must be transformed back to the device positions, using the inverse transform too.

**呈現與反解共用同一個 filter 物件、同一次計算。**（與 MoonClicker 的 `Viewport`
「呈現與觸控讀同一個 instance」是同一個設計原則。）

`PositionMapper.create(Size videoSize, AffineMatrix filterTransform, Size targetSize)`
（`control/PositionMapper.java:18-28`）把該矩陣包上 NDC↔pixel 兩層縮放；
`ScreenCapture.start()`（`:160-174`）決定 `targetSize` 要用哪一個：

```java
if (virtualDisplay == null || displayId == 0) {
    // Surface control or main display: send all events to the original display, relative to the device size
    Size deviceSize = displayInfo.getSize();
    positionMapper = PositionMapper.create(videoSize, transform, deviceSize);
    virtualDisplayId = displayId;
} else {
    // The positions are relative to the virtual display, not the original display (so use inputSize, not deviceSize!)
    positionMapper = PositionMapper.create(videoSize, transform, inputSize);
    virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
}
```

`PositionMapper` 在每次 `capture.start()` 時整個換新
（`Controller.onNewVirtualDisplay`，`Controller.java:166-175`，存進 `AtomicReference`）。

### 過期旋轉：每筆事件帶尺寸戳記，不合就丟

client 端每一筆座標事件都附上「產生它的那張影格的尺寸」。
`app/src/input_manager.c:386`：

```c
msg.inject_touch_event.position.screen_size = im->screen->frame_size;
```

（滑鼠路徑同樣：`:743`、`:807`；型別定義 `struct sc_position` 在 `app/src/coords.h:16-22`，
註解：`The video screen size may be different from the real device screen size, so store to which size the absolute position apply`。）

server 端 `PositionMapper.map(Position)`（`PositionMapper.java:34-47`）：

```java
Size clientVideoSize = position.getScreenSize();
if (!videoSize.equals(clientVideoSize)) {
    // The client sends a click relative to a video with wrong dimensions,
    // the device may have been rotated since the event was generated, so ignore the event
    return null;
}
```

`Controller.getEventPointAndDisplayId()`（`Controller.java:485-512`）拿到 `null`
就整筆丟棄，只留一行 verbose log：

```java
Ln.v("Ignore positional event generated for size " + eventSize + " (current size is " + currentSize + ")");
```

**注意這個機制的實際粒度**：戳記是**尺寸**，不是 rotation。
方形 display 的 0°↔90° 尺寸不變，這道防線就失效
（同一個盲點在 `03878083` 的 reset 路徑上被修掉了，但 `PositionMapper` 沒有一起改）。
以 scrcpy 的目標裝置來說（手機、平板）這個近似成立。

### client 端的座標旋轉是另一段、獨立的

`sc_screen_convert_window_to_frame_coords()`（`app/src/screen.c:1276-1330`）
針對 `--display-orientation` 做八種情況的整數反轉。
這一段**完全在 client 本地**，與裝置方向無關——
它反的是使用者自己按 <kbd>MOD</kbd>+<kbd>←</kbd>/<kbd>→</kbd> 加上去的旋轉
（`apply_orientation_transform`，`app/src/input_manager.c:344-350`）。

---

## Q6. 明文的設計陳述

依重要性排序，全部逐字引用。

1. `doc/develop.md:24-26`：

   > The client is not aware of the device rotation (which is handled by the server), it
   > just knows the dimensions of the video frames it receives.

2. `doc/develop.md:172`：

   > On device rotation (or folding), the encoding session is reset and restarted.

3. `doc/develop.md:368-369`：

   > For the _video_ stream, a _session packet_ (12 bytes) is sent for each capture
   > session (a session changes when the device rotates)

4. `doc/video.md:143-145`（`--capture-orientation` 的 `@` 前綴）：

   > The capture orientation can be locked by using `@`, so that a physical device
   > rotation does not change the captured video orientation

5. commit `45382e3f`（"Add --capture-orientation"）：

   > In addition, --capture-orientation can rotate/flip the display without
   > locking, so that it follows the physical device rotation.

   ——「跟隨裝置的實體旋轉」在這裡是一個**可選的、預設關閉之外的**行為描述；
   「鎖住」與「跟隨」是同一個選項的兩個模式，使用者自己挑。

6. `NewDisplayCapture.java:192-194`（程式碼註解）：

   > However, the virtual display video always remains in its original orientation, so it must be rotated manually.
   > This additional display rotation must not be included in the input events transform (the expected coordinates are already in the
   > physical display size)

7. `NewDisplayCapture.java:154` / `ScreenCapture.java:85` 附近的
   `setSessionDisplayProperties` 註解：

   > Set the current display properties to avoid an unnecessary capture reset

`FAQ.md` 與 `README.md` 沒有任何關於旋轉的段落（grep `-i rotat` 零命中）。

---

## 對 MoonClicker 的意涵

### 結構差異必須先講清楚

scrcpy 的「顯示端」是**桌面視窗**。旋轉發生時它做的事是：換一支影片流、
把視窗按新的長寬比縮放一下（`resize_for_content`，`app/src/screen.c:841-858`）。
**沒有「宿主 Activity 也必須跟著轉」這一環，因為根本沒有宿主 Activity。**

MoonClicker 的鏈是四環，scrcpy 的鏈是兩環（觀察 → 重建）。
MoonClicker 多出來的第 4 環（`activity.requestedOrientation` → display 0 旋轉）
是一次**由系統動畫的、MoonClicker 無法排程也無法與 VD 旋轉對齊的轉場**。
scrcpy 沒有這個問題，因此 scrcpy 的任何手法都**不可能**直接解決它。

### 可以搬過來的

1. **「自己造成的變化不要回饋成新的變化」的結構性防迴授。**
   `DisplayMonitor.setSessionDisplayProperties()` 的做法是：在每次重建 pipeline 之前，
   先把「這一輪是照什麼狀態建的」寫進 monitor，於是自己造成的
   `onDisplayChanged` 比對相等就被吞掉（`DisplayMonitor.java:115-143`）。
   MoonClicker 的鏈上有同樣的風險（我們設 VD rotation → `onDisplayChanged` → 我們改 Activity
   → 可能再引發事件），值得照抄這個「session 狀態先行寫入」的形狀，
   而不是加 timer 去等。
2. **座標事件帶版本戳記，server 端不合就丟**（`PositionMapper.map`，`:34-47`）。
   MoonClicker 目前 `forwardMirrorTouch(event, displayId, transform)`（`MirrorTouch.kt`）
   只送轉換後的座標，服務端無從判斷這個 `Matrix` 是用哪一版幾何算的。
   在 `injectMotionEvent` 的 AIDL 上加一組「事件產生時的 VD 邏輯寬高（或 rotation）」，
   服務端比對不合就丟棄——這是低成本、單向、不影響轉場觀感的正確性補強。
   **但要學到 scrcpy 沒學到的那一課**：戳記應該帶 `rotation`，不能只帶尺寸，
   否則正方形（或長寬相等的裁切）就漏掉了。
3. **旋轉不要 debounce。** scrcpy 一收到就動手，防抖靠狀態比對。
   MoonClicker 現在的 `quantizeOrientation` 遲滯（`BOUNDARY_MARGIN_DEGREES = 30`）
   是必要的，但它防的是**感測器雜訊**，不是顯示狀態抖動——這兩件事不要混為一談。
4. **「顯示多轉的那一層不算進輸入座標反解」**（`NewDisplayCapture.java:192-208`，
   `displayTransform` vs `eventTransform`）。MoonClicker 的 `Viewport` 已經是這個形狀
   （`viewRotationDegrees` 給呈現、`displayPerViewPixel` 給反解），可以當作交叉驗證通過。

### 搬不過來的

1. **「拆掉重建」在 scrcpy 幾乎免費，在 MoonClicker 不是同一回事。** scrcpy 重建的是
   `MediaCodec` + input surface + VD；代價是一個 keyframe 與一次 client 視窗 resize。
   MoonClicker 沒有 encoder，它的「重建」只是一次 recomposition，本來就便宜。
   **MoonClicker 的卡頓不在這一段**，所以 scrcpy 的 reset 設計對 MoonClicker 沒有直接療效。
2. **`--display-orientation` 那一層（client 端自由旋轉畫面）在 MoonClicker 是不可能的。**
   scrcpy 的視窗可以任意方向顯示，因為桌面視窗管理器不在乎。
   MoonClicker 的宿主是 Activity，畫面方向由 display 0 的 rotation 決定，那是系統的。
3. **`ScreenCapture` 的「重建鏡像 VD 就等於旋轉」手法不適用。** 那招成立是因為
   scrcpy 鏡像的是**別人的**、已經轉好的 display 0；MoonClicker 的 VD 就是內容本身，
   重建會把裡面的 app 弄掉。MoonClicker 的對應物是 `NewDisplayCapture`——
   而 `NewDisplayCapture` 的做法正是**保持 VD 尺寸不變、自己在呈現層多轉一次**，
   MoonClicker 的 `graphicsLayer` 旋轉（`VirtualDisplayMirror.kt` / `Viewport.viewRotationDegrees`）
   已經是同一個答案。

### 「拿掉自動旋轉」這個選項

**scrcpy 的預設設計，就是這個選項。** 這不是類比，是逐條對應：

| MoonClicker 現在 | scrcpy |
| --- | --- |
| `OrientationEventListener` 讀感測器 | 完全沒有。全樹沒有任何 `SensorManager` / 角度量化 |
| 跨進程 AIDL → `freezeDisplayRotation(VD)` | 只有 <kbd>MOD</kbd>+<kbd>r</kbd> 一條手動路徑，而且是 toggle 完就 `thawRotation` |
| `onDisplayChanged` → 新幾何 | 一樣（`DisplayMonitor`），這一環 scrcpy 也有 |
| `activity.requestedOrientation` 讓宿主跟著轉 | 沒有對應物 |

也就是說，scrcpy 保留了 MoonClicker 四環中的**第 3 環**（觀察 VD 的實際 rotation 並跟著重算幾何），
把第 1、2 環換成一顆按鍵，第 4 環根本不存在。

對 MoonClicker 而言，「拿掉自動旋轉」實際上要回答的是**第 4 環怎麼辦**——
因為鏡像就在同一塊實體螢幕上，使用者轉動手機時，display 0 會不會轉是一個
必須表態的問題，而 scrcpy 從來不需要表態。兩個各自自洽的落點：

- **(a) 鎖死。** `FullscreenDisplayActivity` 固定一個方向，VD 也不主動轉，
  鏡像按 `Viewport` letterbox。整條鏈只剩第 3 環（app 宣告方向讓 WM 轉 VD 時仍然跟得上）。
  最接近 scrcpy 的 `--capture-orientation=@`（鎖住，實體旋轉不影響）。
- **(b) 放手 + 手動鍵。** Activity 交回系統的自動旋轉（不再 `setRequestedOrientation`），
  VD 的 rotation 交給 WM 依 app 宣告仲裁，另外提供一顆「旋轉這個虛擬顯示」的按鈕
  對應 `Device.rotateDevice` 的語意（toggle `freezeDisplayRotation` 後立刻 `thaw`）。
  最接近 scrcpy 的預設 + <kbd>MOD</kbd>+<kbd>r</kbd>。

兩者都把跨進程 round trip 從轉場的關鍵路徑上移除；差別是 (a) 犧牲了
「橫向 app 佔滿螢幕」，(b) 保留了使用者主動轉的能力但無法保證 Activity 與 VD 同步
（因為兩者本來就由不同的驅動源決定，只是不再由 MoonClicker 串起來製造「應該同步」的期待）。

**誠實的但書**：本文沒有證實 MoonClicker 的卡頓來自兩端不同步。
scrcpy 的設計只能說明「業界最成熟的同類專案不認為感測器驅動 VD 旋轉是必要功能」，
不能說明「拿掉它 MoonClicker 就會順」。如果要保留自動旋轉，scrcpy 這份原始碼裡
沒有任何能讓兩次系統級旋轉轉場對齊的技術——它從頭到尾只有一次。

---

## 引用來源索引

全部出自 `https://github.com/Genymobile/scrcpy`，commit `19c1261`（`v4.1-1-g19c1261`）。

Server（`server/src/main/java/com/genymobile/scrcpy/`）：

- `video/ScreenCapture.java`
- `video/NewDisplayCapture.java`
- `video/SurfaceCapture.java`
- `video/SurfaceEncoder.java`
- `video/CaptureControl.java`
- `video/VideoFilter.java`
- `video/VirtualDisplayListener.java`
- `display/DisplayMonitor.java`
- `display/DisplayProperties.java`
- `display/DisplayPropertiesTracker.java`
- `display/DisplayResizeDebouncer.java`
- `device/Device.java`
- `device/Streamer.java`
- `control/Controller.java`
- `control/PositionMapper.java`
- `model/Orientation.java`
- `opengl/OpenGLRunner.java`、`opengl/AffineOpenGLFilter.java`
- `wrappers/WindowManager.java`、`wrappers/DisplayManager.java`

Client（`app/src/`）：`screen.c`、`input_manager.c`、`demuxer.c`、`decoder.c`、
`recorder.c`、`video_regulator.c`、`coords.h`、`cli.c`

文件：`doc/develop.md`、`doc/video.md`、`doc/virtual-display.md`、`doc/shortcuts.md`

Commit：`eac711ac`、`e26bdb07`、`39d51ff2`、`9b03bfc3`、`03878083`、`7e3b9359`、
`45382e3f`、`bb88b602`、`064635e1`
