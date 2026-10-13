# Android 17 的虛擬顯示器 display group 歸屬與螢幕關閉時的行為（issue #124）

研究日期 2026-10-07，VirtualDevice 途徑實測於 2026-10-13。一手來源為 AOSP `frameworks/base` 的 `android-17.0.0_r1` 原始碼，與 Pixel 7a（API 37，`google/lynx/lynx:17/CP3A.260905.009/16091614:user/release-keys`）以 MoonClicker debug 版、adb shell 與以 shell 身分執行的測試程式（`app_process`）的實測。下文 `F/<path>#N` 指 `https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/<path>#N`。

---

## 結論

1. **Android 17 上，MoonClicker 的 VD 與主螢幕在同一個 display group。** VD 帶著 `FLAG_OWN_DISPLAY_GROUP`，實際落在 `displayGroupId 0`；`dumpsys display` 的 `Display Groups: size=1`。原因是 `separate_timeouts` 開啟時，沒有 layout group name 的顯示器一律由 `DisplayGroupAllocator` 決定 group，虛擬顯示被判成 `primary`，這個決定優先於 `OWN_DISPLAY_GROUP`。
2. **主螢幕關閉時，VD 跟著睡，腳本實際上停擺。** 按電源鍵（`KEYCODE_SLEEP`）後：電源狀態 `Dozing`；VD 的 display state 變成 `OFF`；`dumpsys input` 的 `NonInteractiveDisplays` 列出 0 與 VD；VD 上的 activity 由 resumed 變成 `STOPPED`，`isSleeping=true`。
3. **`separate_timeouts` 在這台正式版是開的，shell 無法關閉。** 它是 `lse_desktop_experience` namespace 的 read-write aconfig 旗標，`device_config` 目前為 `true`。shell 只有 `WRITE_ALLOWLISTED_DEVICE_CONFIG`，沒有 `WRITE_DEVICE_CONFIG`；即使能改，也是全機設定、可能被伺服器同步蓋回，不能當成產品做法。（未實際嘗試寫入，以免改動測試機的全域設定。）
4. **VirtualDevice 途徑可行，但顯示器必須 always unlocked。** 以 shell 身分透過 `com.android.shell` 的 `APP_STREAMING` association 建立 VirtualDevice，再由它建立 trusted、不帶 `OWN_DISPLAY_GROUP` 的顯示器，顯示器會落在自己的 display group 與 power group；主螢幕關閉時這個 group 維持 `Awake`、display state `ON`、不在 `NonInteractiveDisplays`。但沒有 `FLAG_ALWAYS_UNLOCKED` 時，主使用者的 keyguard 也會蓋在這個顯示器上，activity 一樣變成 `STOPPED`。`VirtualDeviceParams` 設 `LOCK_STATE_ALWAYS_UNLOCKED`，或直接在 `VirtualDisplayConfig` 帶 `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED`，activity 都會維持 `RESUMED`。
5. #121 的對策（綁在 displayId 的 wake lock、`sleepVirtualDisplay`）在 Android 17 上只會作用在 group 0，也就是會點亮或關掉主螢幕；MoonClicker 已改成依 VD 實際落在的 group 判斷，見 `docs/virtual-display-pitfalls.md`。

---

## 一、group 怎麼決定

`LogicalDisplayMapper.updateLogicalDisplaysLocked` 為每個顯示器決定 group（`F/services/core/java/com/android/server/display/LogicalDisplayMapper.java#1182` 起）：

| 步驟 | 內容 | 出處 |
|---|---|---|
| 1 | `linkedDeviceUniqueId` = 這個顯示器所屬的 VirtualDevice（`mVirtualDeviceDisplayMapping`），沒有就是 null | `LogicalDisplayMapper.java#1185` |
| 2 | `groupName` = layout 設定給的 group name（裝置的 display layout 設定檔，一般 VD 沒有） | `LogicalDisplayMapper.java#1198` |
| 3 | `separate_timeouts` 開啟且 `groupName` 為空時：`requiredGroupType = decideRequiredGroupTypeLocked(display, type)`，`decidedGroupId` = 名稱等於 `requiredGroupType` 的既有 group，並把 `groupName` 設成它 | `LogicalDisplayMapper.java#1207` |
| 4 | `needsOwnDisplayGroup` = 有 `FLAG_OWN_DISPLAY_GROUP` 或 `groupName` 非空；`needsDeviceDisplayGroup` = 不需要 own group 且屬於某個 VirtualDevice | `LogicalDisplayMapper.java#1216` |
| 5 | `assignDisplayGroupIdLocked`：有 `decidedGroupId` 且不需要 device group 時直接用它；否則屬於 VirtualDevice 就用該裝置的 group；再否則依 own group 旗標配置新 group | `LogicalDisplayMapper.java#1468` |

`DisplayGroupAllocator.decideRequiredGroupTypeLocked`（`F/services/core/java/com/android/server/display/DisplayGroupAllocator.java#68`）只依 content mode 分兩類：`REASON_PROJECTED` → `secondary`，其他（`NON_DESKTOP`、`EXTENDED`、`FALLBACK`）→ `primary`。`getContentModeForDisplayLocked`（同檔 `#121`）在裝置不支援桌面模式時一律回 `NON_DESKTOP`；支援時，`TYPE_VIRTUAL` 也因 `isDesktopModeSupportedOnDisplayLocked` 只認 `INTERNAL`、`EXTERNAL`、`OVERLAY` 而落到 `FALLBACK`。所以虛擬顯示必定是 `primary`。

實機的行為與這份原始碼不完全一致。Pixel 7a 上 group 0 的 `Group Name` 是空字串，不是 `primary`；而照 tag 的原始碼，第 3 步會把 `groupName` 設成非空，使第 4 步的 `needsOwnDisplayGroup` 恆為真、`needsDeviceDisplayGroup` 恆為假，屬於 VirtualDevice 的顯示器也不會進裝置專屬的 group。實測結果卻是 MoonClicker 的 VD（帶 `FLAG_OWN_DISPLAY_GROUP`）落在 group 0，屬於 VirtualDevice 的顯示器落在自己的 group（見第三節）。這台的 build（`CP3A.260905.009`）在這段邏輯與 `android-17.0.0_r1` 有出入，group 歸屬以實測為準。

VirtualDevice 的顯示器要進裝置專屬的 group，`DisplayManagerService.createVirtualDisplayInternal` 要求：沒有 `OWN_DISPLAY_GROUP`、不是 `AUTO_MIRROR`、帶 `TRUSTED`、屬於 VirtualDevice，四者成立時加上 `VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP` 並把顯示器與 VirtualDevice 綁定（`F/services/core/java/com/android/server/display/DisplayManagerService.java#2120`、`#2432`）。

### 旗標本身

`separate_timeouts` 定義在 `F/services/core/java/com/android/server/display/feature/display_flags.aconfig#223`，namespace `lse_desktop_experience`，沒有 `is_fixed_read_only`。Pixel 7a 上：

| 查詢 | 結果 |
|---|---|
| `dumpsys display` 的 `DisplayManagerFlags` | `separate_timeouts: true (def:true)` |
| `device_config get lse_desktop_experience com.android.server.display.feature.flags.separate_timeouts` | `true` |
| shell 的 device config 權限 | 只有 `WRITE_ALLOWLISTED_DEVICE_CONFIG` |
| `aflags list` | `must be root` |

---

## 二、主螢幕關閉時的實測

在 MoonClicker 建立 1080×2400、420 dpi 的 VD（displayId 12），以 `am start --display 12` 開啟設定 App，再以 `input keyevent KEYCODE_SLEEP` 關閉主螢幕，等 4 秒後觀察：

| 項目 | 主螢幕開啟 | 主螢幕關閉 |
|---|---|---|
| VD 的 `displayGroupId`、旗標 | 0；`FLAG_TRUSTED`、`FLAG_OWN_DISPLAY_GROUP`、`FLAG_ALWAYS_UNLOCKED` 等 | 同左 |
| `dumpsys power` | `mWakefulness=Awake` | `mWakefulness=Dozing`，只有 power group 0 |
| VD 的 display state | `ON` | `OFF` |
| `dumpsys input` 的 `NonInteractiveDisplays` | `<none>` | `0`、`10`（MoonClicker 的鏡像 VD）、`12` |
| VD 上的 activity | `topResumedActivity` 為設定 App，`isSleeping=false` | `isSleeping=true`，activity 為 `STOPPED` |

Android 36 起輸入改為逐 display 判斷 interactive（見 `vd-display-group-wake-api31-35.md`），VD 列在 `NonInteractiveDisplays` 代表注入到它的輸入會以非互動狀態處理；加上 activity 已經 `STOPPED`，不會再繪製或回應，腳本的 `vision.*` 等不到畫面變化、`input.*` 也沒有效果。

---


## 三、VirtualDevice 途徑

### 建立條件

| 項目 | 內容 | 出處 |
|---|---|---|
| 權限 | `createVirtualDevice` 要求 `CREATE_VIRTUAL_DEVICE`；shell 持有（`GRANTED_BY_ROLE`） | `F/services/companion/java/com/android/server/companion/virtual/VirtualDeviceManagerService.java#611` |
| association | 必須是呼叫端 package 與 user 名下的 association，profile 限 `AUTOMOTIVE_PROJECTION`、`APP_STREAMING`、`NEARBY_DEVICE_STREAMING`、`VIRTUAL_DEVICE` | 同檔 `#127`、`#622`、`#891` |
| 建立 association | `cmd companiondevice associate 0 com.android.shell <MAC> android.app.role.COMPANION_DEVICE_APP_STREAMING false`。這台的 `cmd_options` 旗標未開，`help` 列的 `--profile`、`--id` 選項無效，參數是位置參數；`disassociate` 同樣以 MAC 指定 | `F/services/companion/java/com/android/server/companion/CompanionDeviceShellCommand.java#107` |
| association 的副作用 | 建立時把 `COMPANION_DEVICE_APP_STREAMING` 角色授予 `com.android.shell`，移除時收回 | 實測 `cmd role get-role-holders` |
| 不需 association 的路徑 | `cmd virtualdevice create-device`、`create-display` 走 `DEVICE_PROFILE_SHELL`，但要求 keyguard 不安全（主使用者設了 PIN 就拒絕，與目前前景使用者無關），顯示器尺寸固定，只適合測試 | `F/services/companion/java/com/android/server/companion/virtual/VirtualDeviceShellCommand.java#82`、`#188` |
| 顯示器旗標 | VirtualDevice 只自動加 `TOUCH_FEEDBACK_DISABLED`、`DESTROY_CONTENT_ON_REMOVAL`、`SUPPORTS_TOUCH`、`OWN_FOCUS`，`LOCK_STATE_ALWAYS_UNLOCKED` 時再加 `ALWAYS_UNLOCKED`；不會加 `OWN_DISPLAY_GROUP` | `F/services/companion/java/com/android/server/companion/virtual/VirtualDeviceImpl.java#176`、`#586` |

### 主螢幕關閉時的實測

測試程式以 shell 身分呼叫 `IVirtualDeviceManager.createVirtualDevice`，再以 `IVirtualDevice.createVirtualDisplay` 建立 1080×2400、420 dpi、`PUBLIC | OWN_CONTENT_ONLY | TRUSTED` 的顯示器；以 `am start --display` 開啟設定 App，再以 `input keyevent KEYCODE_SLEEP` 關閉主螢幕。

| 項目 | 不設 lock state | `LOCK_STATE_ALWAYS_UNLOCKED` 或顯示器帶 `ALWAYS_UNLOCKED` |
|---|---|---|
| `displayGroupId` | 非 0，每個 VirtualDevice 一個 | 同左 |
| `dumpsys power` | group 0 為 `Dozing`，VD 的 group 為 `Awake` | 同左 |
| VD 的 display state | `ON` | `ON` |
| `NonInteractiveDisplays` | 只有 0 | 只有 0 |
| VD 的 keyguard | `KeyguardShowing=true` | `KeyguardShowing=false` |
| VD 上的 activity | `STOPPED`，`isSleeping=true` | `RESUMED`，`isSleeping=false`，46 秒後仍然如此 |

`ALWAYS_UNLOCKED` 需要 `ADD_ALWAYS_UNLOCKED_DISPLAY`，shell 持有。直接在 `VirtualDisplayConfig` 帶這個旗標時，`DisplayManagerService` 只在顯示器屬於 own group 或 device group 時保留它（`F/services/core/java/com/android/server/display/DisplayManagerService.java#2213`）。

VirtualDevice 的 `goToSleep()`、`wakeUp()` 只作用在不屬於 group 0 的 trusted 顯示器（`VirtualDeviceImpl.java#802`、`#816`、`#1841`）；`PowerGroup` 也只對非 0 的 group 讀取 VirtualDevice 的 dim duration 與 screen off timeout（`F/services/core/java/com/android/server/power/PowerGroup.java#131`）。`VirtualDeviceParams` 的 screen off timeout 預設為 0，在 `build()` 時換成無限，所以 VD 不會因逾時而睡（`F/core/java/android/companion/virtual/VirtualDeviceParams.java#1456`）。

### 副作用

| 項目 | 預設 policy 的行為 | 實測 |
|---|---|---|
| 音訊 | `POLICY_TYPE_AUDIO` 為 `DEVICE_POLICY_DEFAULT`：沿用一般的 session id，不導向 VirtualAudioDevice（`VirtualDeviceParams.java#231`） | VD 上 Brave 播放 mp3，`AudioPlaybackConfiguration` 的 `deviceIds:[3]` 即 `AUDIO_DEVICE_OUT_SPEAKER`，聲音照常由手機播放 |
| `app.mute` | appops 以 uid 為單位，與 device 無關 | `PLAY_AUDIO` 設為 `deny` 時 `mutedState:opPlayAudio`，設回 `allow` 時 `mutedState:none` |
| 剪貼簿 | `POLICY_TYPE_CLIPBOARD` 為 `DEVICE_POLICY_DEFAULT`：每個 VirtualDevice 有自己的剪貼簿，不與主裝置共用；改為共用需要 `CUSTOM`、`ADD_TRUSTED_DISPLAY` 且所有顯示器 trusted（`VirtualDeviceParams.java#279`、`VirtualDeviceImpl.java#577`、`#1587`） | `dumpsys clipboard` 的 `mAllowVirtualDeviceSilos: true`；在 VD 上複製的文字只出現在 `User 0 Device <id>`，`Device 0` 不變。MoonClicker 只在 App UI（主螢幕）使用剪貼簿，不受影響 |
| 感測器 | `POLICY_TYPE_SENSORS` 為 `DEVICE_POLICY_DEFAULT`：回傳主裝置的感測器（`VirtualDeviceParams.java#216`） | 未實測 |
| Recents | `POLICY_TYPE_RECENTS` 為 `DEVICE_POLICY_DEFAULT`：trusted 顯示器上的 activity 會出現在主裝置的最近使用畫面（`VirtualDeviceParams.java#248`） | 未實測 |
| 系統設定 | Settings 的 `VirtualDeviceUpdater` 以 `companion:<associationId>` 為 key 列出，名稱取 `AssociationInfo.getDisplayName()`（packages/apps/Settings `connecteddevice/virtual/VirtualDeviceWrapper`） | 「連線偏好設定」下出現一列，一個 association 一列（同一 association 的兩個 VirtualDevice 仍只一列），點進去是「裝置詳細資料」，有「清除」按鈕；通知列沒有對應的通知 |
| 移除 association | `VirtualDeviceManagerService.onCdmAssociationsChanged` 追蹤 association 變更（`VirtualDeviceManagerService.java#450`） | `disassociate` 後 VirtualDevice 立即關閉、顯示器移除、上面的 task 被銷毀（只留在 recents），建立它的 process 沒有收到錯誤 |

---

## 四、待確認

- association 在重開機、Shizuku 重啟後是否保留，以及 MoonClicker 何時建立與移除它。
- 只在 `separate_timeouts` 開啟的版本改用這條路徑；其他版本維持 `OWN_DISPLAY_GROUP`。
- 這台 build 與 `android-17.0.0_r1` 在 group 分配上的差異，要在後續 Android 17 的 QPR 或 tag 上重新比對。
