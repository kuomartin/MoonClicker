# scrcpy `--new-display` 為什麼能讓橫向 App 填滿，`am start --windowingMode` 為什麼不行

研究問題：MoonClicker 用 `DisplayManager.createVirtualDisplay()` + hidden API
`ActivityOptions.setLaunchDisplayId()` 把橫向 Unity 遊戲（`com.noodlecake.altosadventure`）
啟動到自建 VD 上，畫面沒有填滿、留黑邊或被裁切。使用者做了兩個對照實驗：
`scrcpy --new-display=1080x2400 --start-app=...`（成功，VD 建立時的 log 從
`Texture: 1080x2400` 換成 `Texture: 2400x1080`）；`adb shell am start --display X
--windowingMode 1`（失敗，畫面仍未填滿）。要查清楚兩者的原始碼差異，
並確認 `--new-display` 能用而 `am start --windowingMode` 不能用的根本原因。

研究日期 2026-09-21。

**方法**：scrcpy 原始碼讀 `https://github.com/Genymobile/scrcpy`，
commit `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（`v4.1-1-g19c1261`，與既有筆記
`scrcpy-rotation-handling.md` 同一個 commit，方便交叉引用）。AOSP 原始碼讀
`https://android.googlesource.com/platform/frameworks/base`，固定在 tag
`android-15.0.0_r20`（不追 `main` HEAD，避免連結漂移）。下文引用格式為
`路徑:符號名`，commit/tag 用短名或完整 tag 名標注在旁邊。

**沒有驗證的部分**：沒有在真機上跑過 MoonClicker 加上
`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 之後的行為，本文只給出原始碼佐證的
因果鏈，不是「照做保證解決」的實測報告。scrcpy 那條 `INFO: Texture: 1080x2400`
log 的確切原始碼出處**沒有找到逐字比對成功**的版本（見 Q3 但書）。

---

## TL;DR

1. **App 啟動 API 本身沒有差異。** scrcpy 啟動 app 用的就是
   `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)`
   （`Device.startApp()`），跟 MoonClicker 現在用的 hidden API 是同一個。
   `am start --windowingMode` 也只是把同一個 `ActivityOptions` 多設一個
   `setLaunchWindowingMode()`。**兩邊都不是關鍵差異點。**
2. **關鍵差異在建立 VD 時的 flag：`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`。**
   scrcpy `NewDisplayCapture.startNew()` 建立 VD 時固定帶這個 flag；
   AOSP 對它的 javadoc 逐字：沒有這個 flag，DisplayManager 會把內容用
   letterbox/pillarbox 的方式縮放貼進固定方向的畫布；**帶了這個 flag，內容會像
   裝置內建螢幕一樣旋轉去填滿整個顯示區**。這是 AOSP 官方文件語意，不是猜測
   （`DisplayManager.java`，見 Q1）。
3. **這個 flag 的實作路徑是 `LogicalDisplay.configureDisplayLocked()`**：
   有這個 flag，`orientation` 就直接取 `displayInfo.rotation`（跟著內容的邏輯旋轉走），
   然後依 `orientation` 是否為 90°/270° 交換 `physWidth`/`physHeight`。
   **`Texture: 1080x2400` → `2400x1080` 這組數字的變化，量級上完全對應這個
   width/height 交換**，但 scrcpy 那行 log 字串本身在 v4.1 原始碼裡沒有找到
   逐字命中（見 Q3 但書）——這條因果鏈是「flag 語意 + VD 尺寸交換」的原始碼證據，
   不是那行特定 log 文字的證據。
4. **`am start --display --windowingMode` 動的是 Task 的 windowingMode，不是 VD 本身。**
   `--windowingMode` 在 `ActivityManagerShellCommand` 裡就是
   `ActivityOptions.setLaunchWindowingMode()`，只決定這個 Task
   在**它所在的 TaskDisplayArea 裡**要用哪種佈局策略（fullscreen / freeform /
   split-screen secondary…）。`WINDOWING_MODE_FULLSCREEN` 沒有自己的 override
   bounds，就是「填滿父容器（`TaskDisplayArea`/`DisplayContent`）目前的 bounds」。
   **它完全不會改變 `DisplayContent` 本身的 bounds、方向，或觸發 VD resize。**
   如果 VD 建立時沒有 `ROTATES_WITH_CONTENT`，`DisplayContent` 的 bounds
   就是固定在 VD 建立當下的尺寸/方向（例如直向 1080×2400），
   `windowingMode=FULLSCREEN` 只會讓橫向 App 被迫塞進那個直向矩形裡，
   結果就是裁切或黑邊——這正是使用者觀察到的現象。
5. **MoonClicker 要複製 scrcpy 的做法，缺的就是一個 flag，不是換一套啟動 API。**
   在建立 VD 的 `DisplayManager.createVirtualDisplay(name, width, height, dpi,
   surface, flags)` 呼叫裡加上 `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`
   （值為 `1 << 7`，需要跟 MoonClicker 現有的 hidden-api 常數表對齊，見 Q1）。
   scrcpy 同時還監聽 `DisplayInfo` 變化並在 `--flex-display` 模式下呼叫
   `virtualDisplay.resize()`——但這是「使用者拖曳視窗改尺寸」的功能，
   跟「讓內容旋轉塞滿固定尺寸的 VD」是兩件事，**只是填滿橫向內容不需要
   額外的 resize callback**，因為 `ROTATES_WITH_CONTENT` 本身就會讓 framework
   把內容轉正塞進去。

---

## Q1. scrcpy 建立 VD 用的 flag、尺寸邏輯、啟動 API

### VD 建立：固定尺寸建立，flag 決定內容怎麼填進去

`NewDisplayCapture.startNew(Surface)`
（`server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java`，
scrcpy commit `19c1261`）：

```java
public void startNew(Surface surface) {
    try {
        int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
                | VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
        if (vdDestroyContent) {
            flags |= VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        }
        if (vdSystemDecorations) {
            flags |= VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags |= VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        | VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
            }
        }
        VirtualDisplay vd = ServiceManager.getDisplayManager()
                .createNewVirtualDisplay("scrcpy", displaySize.getWidth(), displaySize.getHeight(), dpi, surface, flags);
        ...
    }
}
```

**`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 是固定帶上的，不是 conditional。**
`displaySize` 就是使用者在 `--new-display=1080x2400` 指定的那個直向尺寸——
**VD 建立時的寬高完全照字面值建立，沒有任何「依內容自動 resize」的邏輯在建立這一步**。
（`vdDestroyContent`/`vdSystemDecorations` 兩個才是 conditional，`ROTATES_WITH_CONTENT`
不受它們影響。）

`ServiceManager.getDisplayManager().createNewVirtualDisplay(...)` 的實作
（`server/.../wrappers/DisplayManager.java`）是反射拿一個隱藏的
`DisplayManager(Context)` constructor 建出一個新的 `DisplayManager` 實例，
再呼叫該實例的公開方法
`createVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags)`：

```java
Constructor<android.hardware.display.DisplayManager> ctor =
        android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
ctor.setAccessible(true);
android.hardware.display.DisplayManager dm = ctor.newInstance(FakeContext.get());
return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
```

**這一步呼叫的方法簽章跟一般 App 用的
`DisplayManager.createVirtualDisplay(String, int, int, int, Surface, int)`
是同一個 public API**，scrcpy 繞的只是「怎麼拿到一個能呼叫非 mirror 版本的
`DisplayManager` 實例」，不是換一套 API。**跟 MoonClicker 現有做法的差異，
落在傳進去的 `flags` 參數，不是呼叫的方法本身。**

### `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 的官方語意

`core/java/android/hardware/display/DisplayManager.java`，
AOSP tag `android-15.0.0_r20`（
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/core/java/android/hardware/display/DisplayManager.java>），
javadoc 逐字：

> Virtual display flag: Indicates that the orientation of this display device is coupled to
> the orientation of its associated logical display.
>
> The flag should not be set when the physical display is mounted in a fixed orientation
> such as on a desk. Without this flag, display manager will apply a coordinate transformation
> such as a scale and translation to letterbox or pillarbox format under the assumption that
> the physical orientation of the display is invariant. With this flag set, the content will
> rotate to fill in the space of the display, as it does on the internal device display.

**這段話直接回答了 MoonClicker 的症狀。** 沒有這個 flag，framework 認定
「這塊顯示的物理方向不會變」，於是把橫向內容用 letterbox/pillarbox
（也就是黑邊/裁切）塞進固定方向的畫布；有這個 flag，framework 會把內容
當成裝置內建螢幕一樣轉正填滿。常數值 `1 << 7`（`@SystemApi`，`@hide`）。

### flag 如何真的改變 VD 的 orientation 與尺寸交換

`services/core/java/com/android/server/display/VirtualDisplayAdapter.java`
（同一個 tag）先把呼叫端傳入的 `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`
轉存成 `DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT`：

```java
if ((mFlags & VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT) != 0) {
    mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
}
```

沒有找到任何「與 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 互斥」的檢查——scrcpy 自己就是
`PUBLIC | ROTATES_WITH_CONTENT` 同時帶，且是官方發佈的穩定功能，
可以視為兩者相容（網路上流傳「這個 flag 只能用在 private display」的說法，
在目前 AOSP `main` 分支與 `android-15.0.0_r20` 的 `VirtualDisplayAdapter.java`
裡沒有找到對應的原始碼佐證，判斷是過時或錯誤的二手資訊）。

`services/core/java/com/android/server/display/LogicalDisplay.java`
的 `configureDisplayLocked()`（同一個 tag）才是真正套用這個 flag 的地方：

```java
if ((displayDeviceInfo.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0
        || mAlwaysRotateDisplayDeviceEnabled) {
    orientation = displayInfo.rotation;
}
```

沒有這個 flag，`orientation` 維持 `Surface.ROTATION_0`（VD 的顯示方向被鎖死）；
有這個 flag，`orientation` 直接跟著 `displayInfo.rotation`（也就是這個 logical
display 目前的邏輯旋轉，由裡面跑的 App 的方向宣告驅動）。接著：

```java
boolean rotated = (orientation == Surface.ROTATION_90
                || orientation == Surface.ROTATION_270);
int physWidth = rotated ? displayDeviceInfo.height : displayDeviceInfo.width;
int physHeight = rotated ? displayDeviceInfo.width : displayDeviceInfo.height;
```

**這就是寬高交換的原始碼位置**：`displayDeviceInfo.width/height` 是 VD 建立時
給的物理尺寸（scrcpy 例子裡是 1080×2400，永遠不變），但 `LogicalDisplay`
算出來的 `physWidth/physHeight`——也就是最終暴露給 `DisplayInfo.logicalWidth/
logicalHeight`（App、WindowManager 看到的那個尺寸）——會依目前的
`orientation` 交換成 2400×1080。**scrcpy log 裡「Texture: 1080x2400 →
2400x1080」在量級與方向上，跟這段 `physWidth`/`physHeight` 交換完全吻合。**

---

## Q2. `am start --display --windowingMode` 為什麼沒用

### `--windowingMode` 只是多設一個 `ActivityOptions` 欄位

`services/core/java/com/android/server/am/ActivityManagerShellCommand.java`
（AOSP tag `android-15.0.0_r20`）在 `makeIntent()` 解析參數：

```java
} else if (opt.equals("--display")) {
    mDisplayId = Integer.parseInt(getNextArgRequired());
} else if (opt.equals("--windowingMode")) {
    mWindowingMode = Integer.parseInt(getNextArgRequired());
}
```

在 `runStartActivity()` 套用：

```java
if (mDisplayId != INVALID_DISPLAY) {
    options = ActivityOptions.makeBasic();
    options.setLaunchDisplayId(mDisplayId);
}
if (mWindowingMode != WINDOWING_MODE_UNDEFINED) {
    if (options == null) {
        options = ActivityOptions.makeBasic();
    }
    options.setLaunchWindowingMode(mWindowingMode);
}
```

跟 scrcpy 的 `Device.startApp()`（`server/.../device/Device.java`）幾乎同構：

```java
Bundle options = null;
if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
    ActivityOptions launchOptions = ActivityOptions.makeBasic();
    launchOptions.setLaunchDisplayId(displayId);
    options = launchOptions.toBundle();
}
ActivityManager am = ServiceManager.getActivityManager();
if (forceStop) {
    am.forceStopPackage(packageName);
}
am.startActivity(launchIntent, options);
```

**scrcpy 完全沒有呼叫 `setLaunchWindowingMode()`。** 它只設
`setLaunchDisplayId()`，跟 MoonClicker 現在用的 hidden API 是同一件事。
`--windowingMode` 對 scrcpy 能成功這件事沒有任何貢獻——它壓根沒被用到。

### `WINDOWING_MODE_FULLSCREEN` 決定的是「Task 在父容器裡怎麼佈局」，不是「父容器多大」

`services/core/java/com/android/server/wm/TaskDisplayArea.java` 與
`DisplayContent.java`（同一個 tag）的責任邊界：`TaskDisplayArea` 負責 Task
階層的佈局策略（`resolveWindowingMode()` 決定一個 windowingMode 合不合法、
要不要 fallback），`DisplayContent` 才是真正持有「這個顯示目前的 bounds/方向」
的物件，而它的尺寸來源是 `DisplayInfo`（也就是上一節 `LogicalDisplay` 算出來的
`logicalWidth`/`logicalHeight`）與顯示策略（`DisplayPolicy`），**不受任何
Task 的 windowingMode 影響**——沒有找到 `TaskDisplayArea`/`DisplayContent`
裡任何「windowingMode 改變會回頭觸發 display 尺寸重算」的路徑。

`WINDOWING_MODE_FULLSCREEN` 這個模式本身，沒有為自己的 Task 設定 override
bounds——空的 override bounds 在 Android 的 Configuration 繼承模型裡就是
「照抄父容器目前的 bounds」。也就是說：

- 如果 VD 是**沒有** `ROTATES_WITH_CONTENT` 建立的（`orientation` 鎖死在
  `ROTATION_0`），`DisplayContent` 的 bounds 就固定是 VD 建立時給的那個
  尺寸與方向（例如直向 1080×2400，不會因為裡面跑了橫向 App 而變）。
- `windowingMode=FULLSCREEN` 只是讓 Activity 填滿**這個固定的直向矩形**——
  横向內容要嘛被系統依比例縮放後 letterbox（上下或左右留黑邊），
  要嘛因為 App 本身堅持橫向、與宿主方向衝突而被裁切/位移，
  取決於 App 對 `screenOrientation` 的宣告與 WindowManager 的
  letterbox 策略。**`--windowingMode 1` 在這裡完全是 no-op**，
  因為它從來不是設計來解決「顯示器本身方向不對」這個問題的——
  它是給 freeform / split-screen / pinned 等**同一個 display 內的多視窗佈局**
  用的參數，前提是那個 display 的方向本身已經是對的。

### 結論：Activity 最終 bounds 由 `LogicalDisplay`/`DisplayInfo` 決定，windowingMode 只在那個範圍內二次分配

原始碼上找到的因果鏈是單向的：`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`
（VD 建立時的 flag）→ `LogicalDisplay.configureDisplayLocked()` 的
`orientation`/`physWidth`/`physHeight` → `DisplayInfo.logicalWidth/
logicalHeight` → `DisplayContent` 的 bounds。`windowingMode` 進不了這條鏈的
任何一環，它只消費 `DisplayContent` 算好的 bounds 來決定 Task 怎麼分配這塊
空間。**如果 VD 本身是直向鎖死的，`windowingMode` 設什麼都沒用，
因為它能動的範圍從一開始就被鎖在直向矩形裡。**

---

## Q3. `Texture: 1080x2400` → `2400x1080`：量級證實了，逐字 log 沒找到

`app/src/texture.c`（scrcpy commit `19c1261`）裡唯一與「Texture」相關的 log：

```c
LOGV("Creating new texture: size=%" PRIu16 "x%" PRIu16 " color_space=%d "
     "color_range=%d", size.width, size.height, color_space, color_range);
```

這是 `LOGV`（verbose 等級），格式是
`Creating new texture: size=WxH color_space=... color_range=...`，
跟使用者貼的 `INFO: Texture: 1080x2400` 字面上對不起來（等級字串不同、
沒有 `size=` 前綴、沒有 `color_space`/`color_range`）。全樹搜尋
（GitHub code search，`repo:Genymobile/scrcpy "Texture:"`）也沒有找到
第二個候選。**沒有找到這行使用者貼出的 log 的逐字出處**——可能是使用者的
scrcpy build 版本不同、log 被終端機/其他前端重新格式化，或是這行來自
scrcpy 之外的另一個 wrapper。

但**觸發這行 log（不論它確切文字是什麼）的機制**是可以用原始碼確認的：
`sc_texture_set_from_frame()`（`app/src/screen.c`）在每一個新 frame 到達時
被呼叫，若新 frame 的尺寸與目前 texture 不同就會重建 texture
（`sc_texture_create_frame_texture()`，`app/src/texture.c`）。而 frame
尺寸的變化來源，串回前一份研究筆記 `scrcpy-rotation-handling.md` Q3
已經查證過的機制：`DisplayMonitor` 偵測到 `DisplayProperties`（size + rotation）
變化 → `CaptureControl.reset()` → `SurfaceEncoder` 用新的
`capture.getSize()` 重新 `configure()` MediaCodec → 新的 session packet
宣告新尺寸 → client 端 decoder 收到新尺寸的 frame → 觸發新 texture。
**`1080x2400` → `2400x1080` 這組數字，量級上與 Q1 查到的
`LogicalDisplay` 寬高交換一致：VD 的 `DisplayInfo.getRotation()` 從 0 變成
90°/270°，`NewDisplayCapture.prepare()` 重新計算的 `videoSize`（
`filter.getOutputSize()`）跟著交換寬高，最終傳給 encoder 與 client 的
影格尺寸就從 1080×2400 變成 2400×1080。**

換句話說：這條 log 證明的是「scrcpy 的輸出影格尺寸換了」，
根因（VD 因 `ROTATES_WITH_CONTENT` 而在 `LogicalDisplay` 層交換了
`physWidth`/`physHeight`）在 Q1 已經用另一份原始碼（`LogicalDisplay.java`）
獨立佐證,不需要靠這行 log 文字本身成立。

---

## 對 MoonClicker 的意涵

### 具體要加的東西

1. **`DisplayManager.createVirtualDisplay(name, width, height, densityDpi,
   surface, flags)` 呼叫裡的 `flags` 加上
   `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`（`1 << 7`）。**
   這是 scrcpy 能讓橫向 App 填滿、MoonClicker 現在的做法沒有的**唯一**
   已查證的原始碼差異點——App 啟動 API（`setLaunchDisplayId`）兩邊完全一樣，
   `windowingMode` 兩邊都沒用到（scrcpy 沒用，MoonClicker 加了也沒用）。
   這個常數需要對照 MoonClicker 既有的 hidden-api flag 常數表
   （`hidden-api/` 或 `engine/` 底下，本次研究沒有去核對現有檔案是否已經
   定義過這個值，加之前請先用 `rg VIRTUAL_DISPLAY_FLAG` 確認有沒有現成常數，
   避免重複定義或數值打錯）。
2. **不需要額外的 resize callback 才能「填滿」**——`ROTATES_WITH_CONTENT`
   本身就是 `LogicalDisplay` 用來決定 `physWidth`/`physHeight` 如何隨
   `orientation` 交換的依據，這件事在 framework 端自動發生，
   不需要 MoonClicker 自己監聽 `DisplayInfo` 再手動 `virtualDisplay.resize()`。
   scrcpy 的 `virtualDisplay.resize()`（`NewDisplayCapture.triggerResize()`）
   是給 `--flex-display`（使用者拖曳桌面視窗邊框）用的，跟「內容旋轉填滿」
   是兩個不相關的功能，**不要一起搬**。
3. **如果 MoonClicker 需要知道 VD 目前的實際方向/尺寸**（例如要餵給
   `Viewport`/座標轉換），對應的觀察點是 `DisplayInfo.getRotation()` +
   `DisplayInfo.getSize()`（scrcpy `NewDisplayCapture.prepare()` 讀
   `virtualDisplay.getDisplay().getDisplayId()` 對應的 `DisplayInfo`），
   而不是 VD 建立時傳進去的固定 `width`/`height`——那組數字在
   `ROTATES_WITH_CONTENT` 生效後不會跟著變，變的是 framework 算出來的
   `logicalWidth`/`logicalHeight`。這與既有筆記
   `multi-display-architecture-and-input-routing.md` 若有相關座標假設，
   應該一併檢查是否假設了「VD 尺寸=建立時給的常數」。

### 誠實的但書

本文查證了「`ROTATES_WITH_CONTENT` 的官方語意」與「它在 `LogicalDisplay`
裡如何改變 `physWidth`/`physHeight`」這兩段原始碼，這條因果鏈足以解釋
scrcpy 為什麼能填滿、`am start --windowingMode` 為什麼不能。**但沒有在
MoonClicker 或任何裝置上實測加上這個 flag 之後的行為**——`LogicalDisplay`
的邏輯是通用的 framework 行為，不因呼叫者是 scrcpy 還是 MoonClicker
而不同，所以理論上這個 flag 應該同樣對 MoonClicker 生效；但「理論上同樣生效」
不等於「加了就一定順」，例如 MoonClicker 現有的 rotation 監聽鏈
（`OrientationChain.kt`，見 `scrcpy-rotation-handling.md`）如果跟這個
新的 `orientation` 來源打架，可能需要一併檢視。

---

## 引用來源索引

scrcpy，`https://github.com/Genymobile/scrcpy`，commit `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（`v4.1-1-g19c1261`）：

- `server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java`
  （`startNew()`、`prepare()`、`triggerResize()`）
- `server/src/main/java/com/genymobile/scrcpy/device/Device.java`（`startApp()`）
- `server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayManager.java`
  （`createNewVirtualDisplay()`）
- `server/src/main/java/com/genymobile/scrcpy/wrappers/ActivityManager.java`
  （`startActivity()`）
- `app/src/texture.c`（`sc_texture_create_frame_texture()`）
- `app/src/screen.c`（`sc_texture_set_from_frame()` 呼叫點）

AOSP，`https://android.googlesource.com/platform/frameworks/base`，
tag `android-15.0.0_r20`：

- `core/java/android/hardware/display/DisplayManager.java`
  （`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 常數與 javadoc）
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/core/java/android/hardware/display/DisplayManager.java>
- `services/core/java/com/android/server/display/VirtualDisplayAdapter.java`
  （flag → `DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT` 轉存）
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/display/VirtualDisplayAdapter.java>
- `services/core/java/com/android/server/display/LogicalDisplay.java`
  （`configureDisplayLocked()`：`orientation`/`physWidth`/`physHeight` 計算）
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/display/LogicalDisplay.java>
- `services/core/java/com/android/server/am/ActivityManagerShellCommand.java`
  （`--display`/`--windowingMode` 參數解析與 `ActivityOptions` 套用）
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/am/ActivityManagerShellCommand.java>
- `services/core/java/com/android/server/wm/TaskDisplayArea.java`、
  `services/core/java/com/android/server/wm/DisplayContent.java`
  （windowingMode 解析 vs display bounds 責任邊界，交叉確認用）

官方文件：
<https://source.android.com/docs/core/display/multi_display/activity-launch>
（`setLaunchDisplayId()` 的官方說明；未涵蓋 windowingMode 與 bounds 的關係，
該部分完全依賴上面列的原始碼）。

交叉引用：本文與 `docs/research/scrcpy-rotation-handling.md` 共用同一個
scrcpy commit，`NewDisplayCapture.java`/`Device.java` 的引用可互相對照。
