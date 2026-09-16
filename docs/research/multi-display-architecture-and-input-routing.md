# Android 多顯示器架構研究：Android 9 與 10+ 演進、觸控注入切入點與系統特性

> 研究來源：AOSP 官方文件 [Display support](https://source.android.com/docs/core/display/multi_display/displays) 及相關子頁面 [Input routing](https://source.android.com/docs/core/display/multi_display/input-routing)。  
> 補充對照：AOSP 原始碼（`frameworks/base`、`InputDispatcher`、`InputShellCommand`）與 ReLC / scrcpy 實作經驗。

---

## 1. Android 9 與 Android 10+ 之間的架構變化

Android 10（API 29）是 Android 多顯示器（Multi-Display）與多視窗系統歷史上最關鍵的分水嶺。在 Android 9 及之前，多顯示器支援相當原始且多為硬編碼假定；Android 10 進行了底層系統級的重構。

| 領域 | Android 9 及以前 | Android 10+ |
| :--- | :--- | :--- |
| **Activity 調整大小與相容性** | `resizeableActivity=false` 的 Activity 被直接阻止進入分屏；若使用者嘗試啟動則退出分屏並全屏強制放大，常因讀取 `Application Context` 尺寸或絕對座標導致佈局崩潰。 | 引入 **尺寸相容模式（Size Compatibility Mode, SCM）**。不支援縮放且具固定方向/長寬比的 App 會維持原始比例以 Letterbox/Pillarbox 居中縮放顯示，並由 System UI 提供重啟按鈕。 |
| **顯示器尺寸與長寬比限制** | 次要顯示器有硬性硬體尺寸下限限制（寬/高至少 2.5 吋，`smallestScreenWidth` 至少 320 DP）。 | 取消尺寸下限，支援從極端長條到 1:1 的各類比例；副螢幕可極小（如手錶/智慧外螢幕），App 透過 `android:minHeight`/`android:minWidth` 宣告 opt-in 即可投放。 |
| **顯示策略架構 (Display Policies)** | 由巨石類別 `PhoneWindowManager` (in `WindowManagerPolicy`) 全域集中管理螢幕旋轉、狀態、裝飾視窗與事件追蹤。 | 解耦為 Per-display 模組：`DisplayPolicy`（管理個別顯示狀態、System UI、裝飾視窗、部分按鍵與運動事件）與 `DisplayRotation`（管理個別螢幕旋轉）。 |
| **視窗設定持久化 (`DisplayWindowSettings`)** | 僅儲存極少數全域使用者設定（如全域旋轉鎖定）。 | 擴充為每台顯示器獨立設定，持久化於 `/data/system/display_settings.xml`。包含：預設視窗模式、Overscan、強制尺寸/密度、旋轉模式、移除行為、系統裝飾與 IME 支援。 |
| **顯示器識別碼 (Display Identifiers)** | **不穩定 (Volatile)**。`displayId` 由靜態計數器遞增產生（0=主螢幕, 1=外接螢幕），插拔或重開機時序不同 ID 就會變動；`uniqueId` 僅為硬編碼 `local:0` 或 `local:1`。 | **全系統穩定 (Stable ID)**。引進 `DisplayAddress` 與穩定的 64-bit ID：<br>• Local: `local:<stable-id>`（結合實體埠號與 EDID 資訊）<br>• Network: `network:<mac-address>`<br>• Virtual: `virtual:<package-name-and-name>`。 |
| **實體顯示器數量上限** | SurfaceFlinger 與 `DisplayManagerService` 硬編碼最多僅支援 **2 個實體顯示器**（ID 0 與 1）。 | 依賴 Hardware Composer (HWC) 2.3+ 的 `getDisplayIdentificationData` 讀取 EDID，支援**任意數量**的實體顯示器。 |
| **焦點機制 (Focus Model)** | 全域單一焦點：整個系統在任何時間點**最多只有一個**視窗擁有輸入焦點。 | 支援 **Per-display Focus**（`config_perDisplayFocusEnabled`），允許每個顯示器各自擁有一個獲得焦點的視窗（`InputDispatcher::mFocusedWindowHandlesByDisplay`）。 |

### 詳細實作細節補記

1. **Size Compatibility Mode (SCM) 的判定與座標**：
   - 核心判定在 `ActivityRecord#shouldUseSizeCompatMode()`。
   - 視窗邊界換算由 `AppWindowToken#calculateCompatBoundsTransformation()` 處理，將未宣告可調大小的畫面鎖在原生的 Override Configuration，讓它不依賴外部顯示器的實際物理變動。
   - 變更時，由 System UI 的 `SizeCompatModeActivityController` 監聽回呼並彈出浮動按鈕供使用者手動重啟進程。
2. **副螢幕類型的 Android 10 暫時妥協**：
   - 在 Android 10 中，主螢幕一律標為 `Display.TYPE_INTERNAL`，而所有次要顯示器（即使是折疊機內部的副螢幕）一律被標記為 `Display.TYPE_EXTERNAL`。折疊螢幕設備必須依賴 `DisplayAddress.Physical#getPort` 透過固定硬體連接埠進行辨認。
   - 此問題在 Android 11 引進 HWC 2.4 HIDL `getDisplayConnectionType` 後才得以正式修正（依實際連線型態區分 `TYPE_INTERNAL` 與 `TYPE_EXTERNAL`）。

---

## 2. 操控與注入觸控的切入點（可能性列表）

在 Android 多顯示器架構下，觸控事件必須具備「螢幕關聯（Display Association）」才能正確抵達目標顯示器。以下列出從高層 Framework 到底層 Kernel 的操控切入點：

### 1. Framework Java / Binder API 層（應用層與系統服務）
* **`MotionEvent.setDisplayId(int)` + `InputManager.injectInputEvent(...)`**：
  - **機制**：Android 10（API 29）在 `InputEvent` / `MotionEvent` 新增了 `@hide` 方法 `setDisplayId(int displayId)`。呼叫端在建立 MotionEvent 後，將其標註為目標顯示器的 `displayId`，再透過 `InputManagerService` 的 `injectInputEvent` 注入。
  - **應用**：ReLC（`RelcV2Service`）與 `scrcpy-server` 的標準實作路徑。
* **Shell 命令列工具 (`adb shell input -d <display_id>`)**：
  - **機制**：Android 10 的 `InputShellCommand.java` 擴充了 `-d <display_id>` 參數（例如 `adb shell input -d 2 tap x y`），底層直接為 MotionEvent 設定 displayId 後轉交 injection。
* **測試框架 API (`UiAutomation` / `Instrumentation`)**：
  - **機制**：利用 `UiAutomation.injectInputEvent` 或 `Instrumentation.sendPointerSync`。在 Android 10+ 只要傳入已標註 `displayId` 的事件，底層同樣會透過 InputManager 正確派發至該顯示器。

### 2. Native Input Subsystem 層（InputReader / InputDispatcher）
* **Native `InputDispatcher::injectInputEvent` IPC**：
  - **機制**：直接呼叫 `InputDispatcher` 的 Native Binder 介面。Android 10 的 InputDispatcher 內部以 `mFocusedWindowHandlesByDisplay` 與每顯示器專屬的 Touch State 進行事件投遞，直接在 Native 層將事件打入目標 displayId 的佇列。
* **實體連接埠映射配置 (`input-port-associations.xml`)**：
  - **機制**：AOSP 於 `/vendor/etc/input-port-associations.xml` 提供靜態宣告（`<port display="0" input="usb-xhci-hcd..." />`），由 `EventHub` 透過 `EVIOCGPHYS` 讀取輸入硬體埠，與 `DisplayAddress.Physical#getPort()` 綁定。
* **動態 Viewport 綁定 (`InputReaderConfiguration` / `TouchInputMapper`)**：
  - **機制**：在 InputReader 階段，`TouchInputMapper.mViewport` 會根據裝置位置匹配目標顯示器的 Display Viewport。透過修改或呼叫底層 InputManager 的關聯 API，可動態將特定輸入裝置重導向至指定顯示器的 Viewport。

### 3. Linux Kernel / 虛擬設備層
* **虛擬觸控裝置 (`/dev/uinput`)**：
  - **機制**：使用者空間程式以 `/dev/uinput` 註冊一組虛擬多點觸控板。在 Android 10+ 透過設定對應的 device location/port，或是搭配 Android 13+ 的 `VirtualDeviceManager` (VDM)，將此 uinput 裝置宣告關聯給特定虛擬顯示器。
* **底層 EventHub 節點注入 (`/dev/input/eventX`)**：
  - **機制**：直接向對應硬體觸控節點寫入 Linux `input_event` 結構。前提是該硬體節點已被 AOSP 關聯至目標 display viewport。

### 4. Window / View 階層直接派發（Bypass 系統輸入管道）
* **Root View / ViewRootImpl 直接派發 (`dispatchTouchEvent`)**：
  - **機制**：若是在同一個 App 進程內建立的 `VirtualDisplay`（例如使用 `Presentation`、`ActivityView` 或 `TaskView`），可完全繞過系統的 `InputManagerService` 與權限檢查，直接在本地將合成的 `MotionEvent` 傳遞給視窗的 `DecorView.dispatchTouchEvent(event)`。

---

## 3. 其他感興趣的點與架構細節

在研讀 AOSP 多顯示器官方文件時，有數個對虛擬化與系統控制特別重要的關鍵細節：

### A. 全顯示器共用 VSYNC（Per-display VSYNC is NOT supported）
> *"Per-display VSYNC is not supported. All displays are driven by the VSYNC signal of the primary internal display."*
- **技術意涵**：在 Android 10 甚至更新的版本中，系統並沒有獨立的 per-display VSYNC 渲染驅動。所有次要顯示器（包括外接螢幕、折疊副螢幕、VirtualDisplay）的渲染步伐全部由主螢幕（Primary internal display）的硬體 VSYNC 信號統一節奏驅動。
- **影響**：如果主螢幕關閉、休眠或進入低更新率模式，附屬顯示器的更新時序與流暢度會直接受到牽連。

### B. Per-Display Focus 的安全隱患與 Google 的強烈警告
- **設計初衷**：專為 Android Automotive（車載系統，駕駛與乘客同時操作不同螢幕）設計，讓多個視窗能各自擁有輸入焦點。
- **安全警告**：Google **強烈建議一般消費級設備（手機、平板、PC 模式）不要開啟** `config_perDisplayFocusEnabled`。
- **原因**：若啟用此功能，惡意 App 可在背景偷偷建立一個不可見的 Off-screen VirtualDisplay 並啟動含文字輸入框的 Activity。此時惡意視窗與主螢幕正當視窗同時具備焦點（游標同時閃爍）；而使用者的鍵盤輸入（無論是實體鍵盤或軟體鍵盤）會被頂層視窗截走，造成跨顯示器的機密輸入側錄（Keystroke stealing）。

### C. 顯示器設定持久化檔案 `/data/system/display_settings.xml`
- Android 10 開始，顯示器的旋轉設定、強制解析度（`wm size`）、密度（`wm density`）不再只是全域揮發值，而是會以 `uniqueId` 為鍵值存入 `/data` 分區的 XML 檔案。
- **維護注意事項**：由於檔案存於 `/data`，一旦使用者執行 Factory Reset / Wipe Data，這份自訂螢幕設定就會被清空。

### D. 硬體合成器 (HWC 2.3) 與 EDID 的重要性
- Android 10 能夠打破 2 個螢幕限制，關鍵在於要求 HAL 實現 `IComposerClient@2.3::getDisplayIdentificationData`。
- SurfaceFlinger 負責解析 EDID 中的廠商代碼與序號，將其壓縮合成 64-bit 的穩定 ID。若硬體晶片廠的 HWC 驅動不支援此 HIDL 呼叫，Android 10 會自動 fallback 回 Android 9 的舊版模式（螢幕 ID 隨機遞增，且上限 2 個）。
