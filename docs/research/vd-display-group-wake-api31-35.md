# API 31–35 虛擬顯示器的 display group 歸屬與喚醒途徑（issue #121）

研究日期 2026-09-25。一手來源為 AOSP `frameworks/base`、`frameworks/native` 各 tag 原始碼，Gradle managed device 模擬器（`dev31/33/34/35/36_default_x86_64_Pixel_6`）上的實測，以及實機 Galaxy Note20（SM-N9810，One UI 5.1，API 33，`TP1A.220624.014`）與 Pixel 7a（API 37，`CP3A.260905.009`）以 uid 2000 的實測。下文 `F/<tag>/<path>#N` 一律指 `https://android.googlesource.com/platform/frameworks/base/+/refs/tags/<tag>/<path>#N`，`N/<tag>/…` 指 `frameworks/native`，連結都可以直接點。

---

## 結論

1. **API 31–32 的 VD 在 DEFAULT group（0）。** MoonClicker 在 API 33 以下根本不要求 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`（`privilegedFlags()` 直接回 0），AOSP 12/12L 的 shell 也沒有 `ADD_TRUSTED_DISPLAY`。模擬器 API 31 實測 `displayGroupId 0`、flags 只有 `FLAG_PRESENTATION`，全機只有一個 power group。#121 的前提在 31–32 不成立：VD 跟主螢幕同生共死，預設 group 的 `wakeUp` 就是它的喚醒入口（代價是點亮主螢幕）。
2. **API 33–35 的 VD 在自己的 group（≠ 0）**，前提是 shell 持有 `ADD_TRUSTED_DISPLAY`（AOSP 13 起的 shell manifest 有，OEM 可能拿掉，`ManagedDisplay.ownsDisplayGroup` 會如實記錄）。模擬器 33/34/35 實測 `displayGroupId 1`、`FLAG_OWN_DISPLAY_GROUP`；Note20（One UI 5.1）上 uid 2000 以相同旗標建的 VD 為 `displayGroupId 6`、`FLAG_OWN_DISPLAY_GROUP`。34+ 一併送出的 `DEVICE_DISPLAY_GROUP` 在沒有 virtual device 時是空操作，不影響歸屬。
3. **33–35 上死結是真的，但機制跟 36 不同。** VD 的 `Display.getState()` 在這幾版只反映「有沒有 surface」，group 睡著時仍回報 ON；真正起作用的是每個 display 各自的 `DisplayPowerController` 在 group 關閉時疊上的 **ColorFade 黑色圖層**。實測：33、34 畫面全黑且注入的觸控被 `InputDispatcher` 以「untrusted touch occlusion」丟棄；35（`AE3A.240806.019`）畫面全黑但觸控仍送達。之後的 `userActivity` 對已睡著的 group 無效，所以自己醒不過來。
4. **33–35 可行且已實測有效的解法：綁在 VD displayId 上的螢幕 wake lock。** `PowerManager.newWakeLock(level, tag, displayId)`（@hide，12 起就有）拿 `SCREEN_BRIGHT_WAKE_LOCK`：持有期間該 group 不會逾時；加上 `ACQUIRE_CAUSES_WAKEUP` 取得的那一刻會只喚醒該 group（13 起）。33/34/35/36 四版模擬器都驗證了「叫醒 → 觸控恢復 → 持有 25 秒（逾時設 10 秒）仍醒著」；Note20 實機以 uid 2000、packageName `com.android.shell` 驗證了「只喚醒 VD 的 group 6 → 持有 40 秒（逾時設 15 秒）不逾時 → 釋放當下即因逾時關閉」。需要 `WAKE_LOCK`（shell 有）。
5. **帶 displayId 的 `wakeUp` 不是 36 才有，是 `android-15.0.0_r20`（BP1A，2025-03 QPR2）起就有；** 同一批 tag 也把 VD 狀態改成跟隨電源、輸入改成逐 display 判斷 interactive，也就是 36 那種死結同時出現在 API 35 的較新 build 上。專案的 apiMatrix 結論「36 起」只反映模擬器映像 `AE3A.240806.019`（`android-15.0.0_r1` 那一代）。依賴這個方法就得以方法是否存在判斷，不能看 `SDK_INT`。
6. 不適用：`DisplayManagerGlobal.requestDisplayPower`（API 35，只改 display device 狀態、不碰 PowerGroup，對 VD 回 false）、`SurfaceControl.setDisplayPowerMode`（SurfaceFlinger 直接拒絕 virtual display）、`cmd power`/`cmd display`/`input keyevent WAKEUP`（沒有非預設 group 的喚醒入口）。

---

## 一、VD 屬於哪個 display group

### MoonClicker 實際送出的旗標與身分

| 項目 | 現況 | 出處 |
|---|---|---|
| 呼叫端可指定的旗標 | 只接受 `AUTO_MIRROR`、`DESTROY_CONTENT_ON_REMOVAL`、`SHOULD_SHOW_SYSTEM_DECORATIONS`；app 端實際傳 `SHOULD_SHOW_SYSTEM_DECORATIONS` | `engine/…/service/VirtualDisplayLifecycle.kt:47`、`app/…/ui/displays/DisplaysViewModel.kt:86` |
| 每版都加的旗標 | `PUBLIC`、`PRESENTATION`、`OWN_CONTENT_ONLY`、`SUPPORTS_TOUCH`、`ROTATES_WITH_CONTENT` | `VirtualDisplayLifecycle.kt:53` |
| 特權旗標 | API < 33 回 0；≥ 33 且 `checkSelfPermission(ADD_TRUSTED_DISPLAY)` 成立才加 `TRUSTED`、`OWN_DISPLAY_GROUP`（再視權限加 `ALWAYS_UNLOCKED`，34+ 加 `OWN_FOCUS`、`DEVICE_DISPLAY_GROUP`） | `VirtualDisplayLifecycle.kt:225-247` |
| 被拒時 | `SecurityException` 後改用基本旗標重建，`ownsDisplayGroup` 依實際成功的旗標記錄 | `VirtualDisplayLifecycle.kt:98-110` |
| 身分 | Shizuku 的 shell 進程（uid 2000），`DisplayManager` 透過宣稱 `com.android.shell` 的 context 建立；`PowerManager` 用未包裝的 context | `MoonClickerService.kt:36`、`service/PlatformHandles.kt:31-53,61` |
| 建立 API | `DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags)`，不經 virtual device | `hidden-api-contract/…/DisplayManagerHidden.kt` |

`PUBLIC` 會被 DMS 自動補上 `AUTO_MIRROR`，而 `AUTO_MIRROR` 會剝掉 `OWN_DISPLAY_GROUP`；但 `OWN_CONTENT_ONLY` 先把 `AUTO_MIRROR` 剝掉，所以 `OWN_DISPLAY_GROUP` 得以保留（[F/android-12.0.0_r1/…/DisplayManagerService.java#2570](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#2570) 至 2585）。

### AOSP 的指派邏輯

| 環節 | 行為 | 出處 |
|---|---|---|
| 權限檢查 | 非 system uid 帶 `OWN_DISPLAY_GROUP` 必須持有 `ADD_TRUSTED_DISPLAY`，否則 `SecurityException` | [12 #2624](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#2624)、[13 #1314](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#1314)、[14 #1490](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#1490)、[15 #1659](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#1659) |
| 旗標轉成 device flag | 非 auto-mirror 的 VD 才會設 `DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP` | [VirtualDisplayAdapter 12 #393](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#393)、[15 #486](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#486) |
| 指派 group | 有 `FLAG_OWN_DISPLAY_GROUP`（14 起或有 group name）→ 新 group id；否則 `DEFAULT_DISPLAY_GROUP` | [LogicalDisplayMapper 12 #535](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/LogicalDisplayMapper.java#535)、[14 #854](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/LogicalDisplayMapper.java#854)、[14 #1099](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/LogicalDisplayMapper.java#1099)、[15 #963](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/LogicalDisplayMapper.java#963) |
| `DEVICE_DISPLAY_GROUP`（34+） | 只有 `virtualDevice != null` 才建立關聯，否則只記一行 log；且 `needsDeviceDisplayGroup = !needsOwnDisplayGroup && …`，OWN 優先 | [14 #1637](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#1637)、[LogicalDisplayMapper 14 #887](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/LogicalDisplayMapper.java#887) |
| shell 的權限 | `ADD_TRUSTED_DISPLAY`/`ADD_ALWAYS_UNLOCKED_DISPLAY`：12、12L 沒有；13 起有 | [Shell 12](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/packages/Shell/AndroidManifest.xml)（無此行）、[Shell 13 #667](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/packages/Shell/AndroidManifest.xml#667)、[14 #715](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#715)、[15 #747](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/packages/Shell/AndroidManifest.xml#747) |

### 結果對照

| API | VD 的 group | 依據 | 信心 |
|---|---|---|---|
| 31 | 0 | 程式碼不要求旗標 + 模擬器實測 `displayGroupId 0`、只有 Group 0 | 高 |
| 32 | 0 | 程式碼不要求旗標；12L shell manifest 無 `ADD_TRUSTED_DISPLAY`；未實測（無 AVD） | 高 |
| 33–35 | 自己的 group（模擬器為 1，Note20 為 6） | shell 有權限 + 三版模擬器與 Note20（One UI 5.1，shell `ADD_TRUSTED_DISPLAY: granted=true`）實測 `FLAG_OWN_DISPLAY_GROUP` | 高 |

---

## 二、33–35 上 group 睡著後實際發生什麼

### 模擬器實測

作法：在 repo 的拷貝（不是 repo 本身）加一個 androidTest，沿用 `Tier1Env`（真的 `MoonClickerService`、adopt shell 身分）建 VD、把 puppet 開在上面並清掉它的 `FLAG_KEEP_SCREEN_ON`，`settings put global stay_on_while_plugged_in 0`、`settings put system screen_off_timeout 10000`，每 2 秒對 display 0 呼叫 `userActivity` 讓主螢幕保持醒著，等 25 秒讓 VD 的 group 自己逾時；之後依序嘗試各種喚醒方式，每一步都抓 `dumpsys display`/`dumpsys power`/`dumpsys SurfaceFlinger --list`、注入一次 tap 看 puppet 有沒有收到、掛 `ImageReader` 取樣影格。測完還原設定並 `adb emu kill`。模擬器以 `-read-only` 啟動，設定不落地。

| API/build | VD group | 逾時後（主螢幕醒著） | tap | 影格 | 出處 |
|---|---|---|---|---|---|
| 31 `SE1A.220826.006.A1` | 0 | 不適用（與主螢幕同一 group） | 此映像一律收不到 | 此映像一律單色 | 與 `docs/lua-api-testing.md` 記錄的 31 模擬器限制一致 |
| 33 `TE1A.220922.034` | 1 | `PowerGroup: Powering off display group due to timeout (groupId= 1)`；`Display.state` 仍 2（ON）；DMS 內 `mDisplayState=OFF`；SF 出現 `ColorFade#153`/`ColorFade BLAST#154` | **丟失**（`InputDispatcher: Dropping untrusted touch event due to /1000`） | 全黑（231 張、非黑取樣 0） | 實測 |
| 34 `UE1A.230829.036.A1` | 1 | 同上；`InputDispatcher` 列出遮蔽者 `window={ColorFade BLAST#155}` | **丟失** | 此映像一律全黑，無法判讀 | 實測 |
| 35 `AE3A.240806.019` | 1 | 同上；`RequestedLayerState{ColorFade#153 z=1073741825 layerStack=2}` | 收到 | **全黑**（醒著時 881 個非黑取樣） | 實測 |
| 36 `BE2A.250530.026.D1`（對照） | 1 | `Display.state` 變 1（OFF）、puppet 被 pause | **丟失** | — | 實測 |

`Display.getState()` 在 33–35 不變，是因為 `VirtualDisplayAdapter` 把 `mInfo.state` 寫成 `mIsDisplayOn ? ON : OFF`，而 `mIsDisplayOn` 只看有沒有 surface；電源狀態只進 `mDisplayState` 和 `VirtualDisplay.Callback`（[12 #296](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#296)、[12 #433](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#433)、[14 #560](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#560)、[15_r1 #563](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#563)）。WindowManager 對非預設 display 的 sleep token 看的正是這個 state（[DisplayContent 12 #5401](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/wm/DisplayContent.java#5401)、[15 #6150](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/wm/DisplayContent.java#6150)），所以 33–35 的 activity 不會被 pause。這也解釋了 `docs/lua-api-testing.md` 裡「34, 35：`goToSleep` 回傳成功，顯示器維持 ON」——`VirtualDisplayIdleDeadlockTest` 等 `Display.state == OFF` 當前提，在 36 以前永遠等不到。

黑畫面來自 `DisplayPowerController`：DMS 為每一個 logical display（含 VD）建一個 DPC（[DMS 12 #1246](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#1246)、[#2088](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#2088)），ColorFade 除低 RAM 裝置外一律啟用（[DPC 13 #627](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/display/DisplayPowerController.java#627)、[DPC2 14 #588](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayPowerController2.java#588)、[DPC 15 #641](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/DisplayPowerController.java#641)），group 關閉時把 ColorFade level 調到 0，疊在該 display 的 layer stack 最上層。

觸控被丟的機制：ColorFade 由 system_server（uid 1000）建立，`createSurfaceControl` 只設名稱、secure 與 layer stack，沒有標 trusted overlay（[ColorFade 13 #572](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/display/ColorFade.java#572)、[14 #573](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/ColorFade.java#573)、[15 #609](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/ColorFade.java#609)）。它的子層 `ColorFade BLAST` 是 buffer layer，SurfaceFlinger 會替有 buffer 的 layer 產生只供遮蔽判斷的 window info（[N 13 BufferLayer.h #200](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-13.0.0_r1/services/surfaceflinger/BufferLayer.h#200)、[N 14 Layer.h #522](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-14.0.0_r1/services/surfaceflinger/Layer.h#522)、[N 15 LayerSnapshotBuilder #282](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/services/surfaceflinger/FrontEnd/LayerSnapshotBuilder.cpp#282)）。`InputDispatcher::canBeObscuredBy` 把 uid 不同、非 trusted overlay、同 display 的可見視窗算成遮蔽者，touch 因此以 untrusted occlusion 被丟（[N 13 #2653](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-13.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp#2653)、[#2167](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-13.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp#2167)；[N 14 #2908](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-14.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp#2908)；[N 15 #3090](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp#3090)、[#5263](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp#5263)）。`ColorFade.createSurfaceControl` 與 `canBeObscuredBy` 在 13、14、15 的 r1 逐字相同，35 模擬器觸控仍送達的原因不在這兩處，未查明；方案 A 讓 group 不睡、ColorFade 不出現，這個差異不影響方案。

36 的行為改變有兩處：VD 狀態改由 `mDisplayState` 決定（[16 VirtualDisplayAdapter #668](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#668)），輸入改問 `isDisplayInteractive(displayId)`（[16 jni #1873](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/jni/com_android_server_input_InputManagerService.cpp#1873)、[IMS 16 #3669](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/input/InputManagerService.java#3669)）。這兩處在 `android-15.0.0_r17` 仍是舊版、`android-15.0.0_r20` 起已是新版（[r20 VirtualDisplayAdapter #642](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#642)、[r20 jni #1762](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/jni/com_android_server_input_InputManagerService.cpp#1762)、[r20 Notifier #547](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/power/Notifier.java#547)）。`r20` 對應 build `BP1A.250305.019`（Pixel 6–8 系列 2025-03 更新，見 [build numbers](https://source.android.com/docs/setup/reference/build-numbers)）。

### 主螢幕關掉時

31–35 的輸入閘門是全域的：`NativeInputManager::interceptMotionBeforeQueueing` 對注入事件只看 `mInteractive`（[12 #1237](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/jni/com_android_server_input_InputManagerService.cpp#1237)、[14 #1370](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/jni/com_android_server_input_InputManagerService.cpp#1370)、[15_r1 #1479](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/jni/com_android_server_input_InputManagerService.cpp#1479)），而它來自全域 wakefulness（[Notifier 13 #440](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/Notifier.java#440)），全域 wakefulness 取所有 group 裡最醒的那個（[PMS 13 #2191](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#2191)）。實測（33/34/35）：VD group 醒著時按 `KEYCODE_SLEEP` 關主螢幕，`PowerManager.isInteractive()` 仍為 true、tap 照收；兩個 group 都睡了以後 `isInteractive()` 變 false、tap 丟失。31 的 VD 在 group 0，主螢幕一關 VD 的 `mDisplayState` 就跟著 OFF。

---

## 三、方案比較

### A. 綁 VD displayId 的螢幕 wake lock（建議）

| 欄位 | 內容 |
|---|---|
| 適用 API | 保持醒著：12 起（group 在 31–32 就是主螢幕的 group）；只喚醒該 group：13 起 |
| 做法 | `PowerManager.newWakeLock(SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP, tag, vdDisplayId)`，VD 存活期間持有、`destroyVirtualDisplay` 時釋放。要把已睡著的 group 叫醒（例如 34+ 的 `sleepVirtualDisplay` 之後）就釋放再重新 acquire，`ACQUIRE_CAUSES_WAKEUP` 只在 acquire 那一刻生效 |
| 權限、身分 | `WAKE_LOCK`（shell 有）。`ACQUIRE_CAUSES_WAKEUP`：13 要求 wake lock 的 packageName 是與 calling uid 相符的 privileged app，或 `OP_TURN_SCREEN_ON` 為 allowed（Shell 是 `privileged: true`；`OP_TURN_SCREEN_ON` 預設 `MODE_ALLOWED`，[AppOpsManager 13 `sOpDefaultMode`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/app/AppOpsManager.java)，但 AppOps 會先核對 packageName 是否屬於 calling uid，所以 packageName 仍須是 `com.android.shell`）；14、15 要求 `TURN_SCREEN_ON` 或 compat change `REQUIRE_TURN_SCREEN_ON_PERMISSION` 未啟用——該 change 是 `@EnabledSince(CUR_DEVELOPMENT)`，發行版上對所有 app 都未啟用，模擬器 log 也印出 `Allowing device wake-up without android.permission.TURN_SCREEN_ON` |
| 副作用 | VD group 醒著會讓全域 wakefulness 維持 Awake：主螢幕關了裝置仍算 interactive，不進 Doze，耗電與「用手機跑腳本、螢幕關著」的使用情境一致但要讓使用者知道。預設 group 的 `goToSleep`（電源鍵）不會動到 VD group。13 在預設 group 睡著而全域仍醒時會觸發 keyguard timeout（[PhoneWindowManager 13 #4516](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java#4516)）。12 的 `ACQUIRE_CAUSES_WAKEUP` 會喚醒所有 group（含主螢幕），但 31–32 的 VD 本來就在 group 0 |
| 證據 | wake lock 依 displayId 歸入 group：[PMS 13 #5135](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#5135)；逐 group 彙總 wake lock：[PMS 13 #2550](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#2550)；螢幕鎖在 Awake 時隱含 `STAY_AWAKE`：[#2613](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#2613)；逾時判斷看 group 的 `STAY_AWAKE`：[#3127](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#3127)；`ACQUIRE_CAUSES_WAKEUP` 只喚醒該 group：[13 #1595](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1595)、[15 #1742](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1742)（12 是全部 group：[12 #1449](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1449)）；權限判斷：[13 #1569](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1569)、[14 #1662](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1662)、[15 #302](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#302)；`newWakeLock(int,String,int)` @hide：[PowerManager 12 #1223](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/os/PowerManager.java#1223)、[15 #1372](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/os/PowerManager.java#1372)。實測 33/34/35/36：`PowerGroup: Waking up power group from Dozing (groupId=1, … details=MoonProbe:vd)`，隨後 tap 收到、35 的影格恢復內容；持有 25 秒（逾時 10 秒）group 仍醒著。實機（uid 2000 的 `app_process` 直接呼叫 `IPowerManager.acquireWakeLock`，packageName `com.android.shell`）：Note20 API 33 上 VD（displayId 8）`PowerGroup: Waking up power group from Dozing (groupId=6, uid=2000, …)`，主螢幕維持關閉，持有 40 秒（逾時 15 秒）無逾時，釋放當下 `Powering off display group due to timeout (groupId= 6)`；Pixel 7a API 37 上 `Allowing device wake-up without android.permission.TURN_SCREEN_ON for com.android.shell` |
| 信心 | 高（原始碼 + 四版模擬器 + Note20 實機的 production 身分）。MoonClicker 本身的程式路徑尚未在實機跑過 |

WindowManager 本來就用同一機制：VD 上有 `FLAG_KEEP_SCREEN_ON` 視窗時，dumpsys power 出現 `SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:N'`，group 的 `mWakeLockSummary=0x23`，該 group 不會逾時（34、35 實測）。所以跑在 VD 上的遊戲若自己設了 keep-screen-on，就不會踩到 #121；#6 當初 25 分鐘閒置沒重現，這也是可能原因之一。

實作時要注意 `PlatformHandles.powerManagerHidden` 目前用未包裝的 context 取 `PowerManager`，wake lock 帶上去的 packageName 是那個 context 的 `getOpPackageName()`；13 的 privileged 判斷與 AppOps 都要求它屬於 calling uid，必須比照 `fakeDisplayContext` 宣稱 `com.android.shell`。

`ACQUIRE_CAUSES_WAKEUP` 只在 acquire 那一刻生效，釋放後若已超過逾時，group 會立刻關閉（Note20 實測）。所以「只在需要時持有」的用法等於：需要時 acquire（順便叫醒），不需要時 release（隨即可能睡著）。

acquire 後立即 release（pulse）可以當作帶 displayId 的 `wakeUp` 用。Note20 實測（主螢幕關閉、鎖定中）：

- **pulse 本身：** 叫醒 group 後約 6 秒再次逾時，加 `ON_AFTER_RELEASE` 也一樣。
- **pulse 後注入輸入：** 之後用 `input -d <id> tap` 注入的觸控會重設該 group 的計時，group 在最後一次觸控約 6 秒後才逾時。
- **不鎖定時：** 同一台機器在 `screen_off_timeout=120000`、主螢幕剛喚醒的情況下，VD group 33 秒內沒有逾時。

6 秒不是 `screen_off_timeout`（15 秒），推測是鎖定畫面時 WindowManager 設下的 user activity timeout override 作用到所有 group，這點未確認。持有中的 wake lock 不受影響：同樣在鎖定狀態下持有 40 秒都沒有逾時。

### B. 定期 `userActivity(displayId)`

| 欄位 | 內容 |
|---|---|
| 適用 API | 12 起（`IPowerManager.userActivity(int displayId, …)`） |
| 做法 | 以短於 `screen_off_timeout` 的間隔呼叫 `IPowerManager.userActivity(vdDisplayId, now, OTHER, 0)`，或對 VD 的 display context 取 `PowerManager` 呼叫 `userActivity(long,int,int)`（它帶 `mContext.getDisplayId()`） |
| 權限、身分 | `DEVICE_POWER` 或 `USER_ACTIVITY`（shell 有 `DEVICE_POWER`） |
| 副作用 | 只能延長醒著的 group，對已睡著（Asleep/Dozing）的 group 直接忽略；要自己維護計時器，錯過一次就回到死結。全域 interactive 的副作用同 A |
| 證據 | [IPowerManager 12 #45](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/os/IPowerManager.aidl#45)；displayId → group：[PMS 12 #1676](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1676)；睡著時忽略：[12 #1749](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#1749)、[15 #2154](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#2154)；[PowerManager 12 #1310](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/os/PowerManager.java#1310)。實測 33/34/35：對睡著的 group 1 呼叫後仍是 Dozing |
| 信心 | 高。可行但比 A 脆弱，沒有理由優先 |

### C. 33–35 不用 `OWN_DISPLAY_GROUP`

| 欄位 | 內容 |
|---|---|
| 適用 API | 33–35 |
| 做法 | `privilegedFlags()` 在 33–35 不加 `OWN_DISPLAY_GROUP`，VD 落回 group 0 |
| 權限、身分 | 不需要 `ADD_TRUSTED_DISPLAY` 的這一條檢查（`TRUSTED` 仍需要） |
| 副作用 | VD 跟主螢幕同一個逾時與電源鍵：主螢幕一關 VD 就黑、輸入被全域閘門擋下，喚醒只能點亮主螢幕，正是 #6 當初刻意用這個旗標避開的事。33 上 `ALWAYS_UNLOCKED` 必須搭配 `OWN_DISPLAY_GROUP` 才生效（[VirtualDisplayAdapter 13 #458](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#458)），拿掉就失去鎖屏時的觸控；34–35 則因同時帶 `DEVICE_DISPLAY_GROUP` 旗標而保留（[14 #521](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#521)） |
| 證據 | 同上；31 模擬器即為此狀態的實例 |
| 信心 | 高。不建議：拿隔離換掉一個 A 就能解的問題 |

### D. 延長、停用 group 逾時

| 欄位 | 內容 |
|---|---|
| 適用 API | 12 起 |
| 做法 | `screen_off_timeout` 是所有 group 共用的設定；`stay_on_while_plugged_in`（`mStayOn`）讓所有 group 在充電時不逾時 |
| 權限、身分 | `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS`（shell 有） |
| 副作用 | 改的是使用者的全域設定，主螢幕一起不關；`mStayOn` 只在充電時有效。沒有「只延長某個 group」的設定——A 就是那個只作用在單一 group 的版本 |
| 證據 | `isBeingKeptAwakeLocked` 對所有 group 都先看 `mStayOn`：[PMS 15 #3365](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#3365)；模擬器預設 `stay_on_while_plugged_in=1` 時 VD group 從不逾時（實測 35） |
| 信心 | 高。不建議 |

### E. 帶 displayId 的 `wakeUp`

| 欄位 | 內容 |
|---|---|
| 適用 API | 36；以及 35 的 `android-15.0.0_r20`（BP1A）以後的 build |
| 做法 | `PowerManager.wakeUp(long,int,String,int displayId)`（@hide）→ `IPowerManager.wakeUpWithDisplayId`。以反射或 contract 查方法是否存在來決定，不以 `SDK_INT` |
| 權限、身分 | `DEVICE_POWER` |
| 副作用 | 無額外副作用；只解決喚醒，不防止逾時 |
| 證據 | [PowerManager r20 #1665](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/core/java/android/os/PowerManager.java#1665)、[PMS r20 #6027](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/power/PowerManagerService.java#6027)、[IPowerManager 16 #56](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/os/IPowerManager.aidl#56)；`android-15.0.0_r1` 的 IPowerManager 沒有這個方法（[15 #48](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/os/IPowerManager.aidl#48)）。實測 36：`Waking up power group from Dozing (groupId=1, … details=MoonClicker own-display-group keep-awake)`；35 模擬器（`AE3A`）上方法不存在 |
| 信心 | 高 |

### F. 不適用的途徑

| 途徑 | 為什麼不適用 | 出處 |
|---|---|---|
| 預設 group 的 `wakeUp(long,…)`/`input keyevent WAKEUP` | binder 實作寫死 `DEFAULT_DISPLAY_GROUP`，只點亮主螢幕 | [PMS 12 #5282](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#5282)、[13 #5623](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#5623)、[14 #5860](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#5860)、[15 #6009](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#6009) |
| `DisplayManagerGlobal.requestDisplayPower(displayId, on)`（35） | 直接呼叫 display device 的 `requestDisplayStateLocked`，不經 PMS/DPC，不改 PowerGroup wakefulness；VD 的實作回傳 null runnable，所以方法記 log 後回 false。需要 `MANAGE_DISPLAYS`（shell 從 15 起有，[Shell 15 #890](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/packages/Shell/AndroidManifest.xml#890)）。實測 35：回 false、`W DisplayManagerService: requestDisplayPower: Cannot update the power state to ON=true … runnable is null`、group 1 仍 Dozing；副作用是 `dumpsys display` 的 `mDisplayState` 被改成 ON，看起來像醒了其實沒有。36 簽名改成 `(int, int state)`，同樣回 false | [DMS 15 #3409](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#3409)、[#4657](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/DisplayManagerService.java#4657)、[VirtualDisplayAdapter 15 #385](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/VirtualDisplayAdapter.java#385)、[IDisplayManager 15 #241](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/hardware/display/IDisplayManager.aidl#241) |
| scrcpy 的 `USE_ANDROID_15_DISPLAY_POWER` | scrcpy 在 35 上曾改用 `requestDisplayPower` 關螢幕（commit `58ba00f`），但在 Android 15 上關螢幕後鏡像畫面凍結，隨即退回舊方法並把開關預設為 false（commit `3d1f036`，Fixes Genymobile/scrcpy#5530）。它的用途是關實體面板，跟喚醒 VD 的 group 是兩件事 | [scrcpy Device.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/device/Device.java)、[3d1f036](https://github.com/Genymobile/scrcpy/commit/3d1f036c04412e17a694e6a0b857b7f9e9217ab3)、[58ba00f](https://github.com/Genymobile/scrcpy/commit/58ba00fa060c9a1f439120f8869ed106e1c935f9) |
| `SurfaceControl.setDisplayPowerMode`/`DisplayControl`（scrcpy 預設路徑） | 只拿得到實體 display 的 token；SurfaceFlinger 對 virtual display 一律拒絕（`Attempt to set power mode … for virtual display`、`Invalid operation on virtual display`）。而且它只改面板電源，不改 PowerGroup | [N 12 #4624](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-12.0.0_r1/services/surfaceflinger/SurfaceFlinger.cpp#4624)、[#4538](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-12.0.0_r1/services/surfaceflinger/SurfaceFlinger.cpp#4538)、[N 15 #6334](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/services/surfaceflinger/SurfaceFlinger.cpp#6334)、[#6183](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-15.0.0_r1/services/surfaceflinger/SurfaceFlinger.cpp#6183) |
| `cmd power` | 12–15 只有省電模式、ambient display、prox wake lock 等子指令，沒有喚醒 | [PowerManagerShellCommand 12](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/core/java/com/android/server/power/PowerManagerShellCommand.java)、[15](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/power/PowerManagerShellCommand.java) |
| `cmd display power-on/power-off` | 15 才有，內部就是上面的 `requestDisplayPower` | [DisplayManagerShellCommand 15 #600](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/display/DisplayManagerShellCommand.java#600) |
| `goToSleepWithDisplayId`（34+，`sleepVirtualDisplay` 用的） | 方向相反，只能讓 group 睡 | [PMS 14 #5896](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java#5896) |

---

## 四、對現有程式碼與文件的影響

| 位置 | 現況 | 與本研究的出入 |
|---|---|---|
| `VirtualDisplayLifecycle.wakeDisplayGroupIfOwned` 的 KDoc 與 #121 內文 | 「API 31–35 的 VD 同樣有獨立 display group」 | 31–32 沒有；33–35 才有 |
| `wakeDisplayGroupIfOwned` 的版本閘門 | `SDK_INT < BAKLAVA` 就返回 | 35 QPR2（`android-15.0.0_r20`）以後有這個方法，也有 36 式的死結；以 `SDK_INT` 判斷會漏掉這些 build |
| `hidden-api/…/PowerManagerHidden.java`、`hidden-api-contract/…/PowerManagerHidden.kt` | `wakeUp(…, displayId)` 標 `sinceApi = BAKLAVA` | 只對 `AE3A` 映像成立 |
| `VirtualDisplayIdleDeadlockTest` 與 `docs/lua-api-testing.md` 的 34/35 列 | 等 `Display.state == OFF` 當作「睡著了」的前提 | 36 以前 VD 的 `Display.state` 不反映電源；33–35 應改觀察 PowerGroup（logcat `PowerGroup`）、ColorFade 圖層或 tap 是否送達 |

---

## 五、尚未確認的事項

| # | 項目 | 怎麼確認 | 影響 |
|---|---|---|---|
| 1 | MoonClicker 自己的程式路徑（Shizuku UserService、`PlatformHandles` 取得的 `PowerManager`）在實機上取得綁 VD 的 wake lock | 實作後在 Note20：`settings put system screen_off_timeout 15000`、`settings put global stay_on_while_plugged_in 0`，跑腳本讓 VD 閒置超過逾時，`adb logcat \| grep PowerGroup` 不應出現該 group 的 `Powering off`；`dumpsys power` 可見綁 VD displayId 的 `SCREEN_BRIGHT_WAKE_LOCK`。測完還原兩個設定 | 驗收條件 |
| 2 | SM-A217F（API 31/32）的 VD 是否在 group 0 | 建 VD 後 `dumpsys display \| grep displayGroupId`、`dumpsys power \| grep -A6 "Display Group User Activity"` | 確認 31–32 不需處理 |
| 3 | API 35 QPR2 以後的 build 上帶 displayId 的 `wakeUp` 是否存在 | 需要一台 BP1A 以後的 API 35 裝置，反射查 `PowerManager.wakeUp(long,int,String,int)` | 以方法偵測取代 `SDK_INT` 閘門後，結果不影響實作，只影響文件描述 |
| 4 | 35 模擬器上 ColorFade 為何不擋觸控 | 需比對 SurfaceFlinger 在 `AE3A.240806.019` 的 layer snapshot 行為 | 不影響方案 |
