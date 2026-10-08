# Android 17 的虛擬顯示器 display group 歸屬與螢幕關閉時的行為（issue #124）

研究日期 2026-10-07。一手來源為 AOSP `frameworks/base` 的 `android-17.0.0_r1` 原始碼，與 Pixel 7a（API 37，`google/lynx/lynx:17/CP3A.260905.009/16091614:user/release-keys`）以 MoonClicker debug 版與 adb shell 的實測。下文 `F/<path>#N` 指 `https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/<path>#N`。

---

## 結論

1. **Android 17 上，MoonClicker 的 VD 與主螢幕在同一個 display group。** VD 帶著 `FLAG_OWN_DISPLAY_GROUP`，實際落在 `displayGroupId 0`；`dumpsys display` 的 `Display Groups: size=1`。原因是 `separate_timeouts` 開啟時，沒有 layout group name 的顯示器一律由 `DisplayGroupAllocator` 決定 group，虛擬顯示被判成 `primary`，這個決定優先於 `OWN_DISPLAY_GROUP`。
2. **主螢幕關閉時，VD 跟著睡，腳本實際上停擺。** 按電源鍵（`KEYCODE_SLEEP`）後：電源狀態 `Dozing`；VD 的 display state 變成 `OFF`；`dumpsys input` 的 `NonInteractiveDisplays` 列出 0 與 VD；VD 上的 activity 由 resumed 變成 `STOPPED`，`isSleeping=true`。
3. **`separate_timeouts` 在這台正式版是開的，shell 無法關閉。** 它是 `lse_desktop_experience` namespace 的 read-write aconfig 旗標，`device_config` 目前為 `true`。shell 只有 `WRITE_ALLOWLISTED_DEVICE_CONFIG`，沒有 `WRITE_DEVICE_CONFIG`；即使能改，也是全機設定、可能被伺服器同步蓋回，不能當成產品做法。（未實際嘗試寫入，以免改動測試機的全域設定。）
4. **唯一看得到的出路是 VirtualDevice。** 屬於某個 VirtualDevice、且不要求 own group 的顯示器，會被分到該 VirtualDevice 專屬的 group，不受 `DisplayGroupAllocator` 的決定影響。shell 持有 `CREATE_VIRTUAL_DEVICE`（`GRANTED_BY_ROLE`），`cmd companiondevice associate` 可以建立 association。是否真能讓 VD 與主螢幕分開睡醒、以及副作用，需要原型驗證。
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

主螢幕所在的 group 0 在同一條路徑上被命名為 `primary`，因此第 3 步找到的 `decidedGroupId` 就是 0；第 5 步的第一個條件成立，`FLAG_OWN_DISPLAY_GROUP` 完全沒有作用。

例外只有第 5 步的 `needsDeviceDisplayGroup`：VD 屬於 VirtualDevice、而且沒有要求 own group 時，`decidedGroupId` 被略過，改用 `mDeviceDisplayGroupIds` 為該 VirtualDevice 配置的 group。

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

## 三、VirtualDevice 途徑的前提與待驗證事項

| 項目 | 現況 |
|---|---|
| 建立 VirtualDevice 的權限 | shell 持有 `CREATE_VIRTUAL_DEVICE`（`granted=true, flags=[GRANTED_BY_ROLE]`） |
| CompanionDevice association | `cmd companiondevice associate USER_ID PACKAGE --profile ...` 可建立；能否以 `com.android.shell` 建立 `APP_STREAMING` 等 profile 的 association、在 user build 上是否可用，未驗證 |
| VD 建立方式 | 改以 `VirtualDevice.createVirtualDisplay` 建立，且不帶 `OWN_DISPLAY_GROUP`，才會走 `needsDeviceDisplayGroup` |

需要原型回答的問題：

- VD 是否真的拿到非 0 的 group，主螢幕關閉時能否維持 `ON`、interactive、activity resumed；它的逾時與喚醒是否能沿用 #121 的 wake lock 做法。
- 在 VirtualDevice 上執行的 app 會拿到該裝置的 context：音訊是否被導到 VirtualDevice（例如 YouTube 範例變成沒有聲音）、感測器、剪貼簿、`app.mute` 的 appops 是否仍有效。
- association 是否會出現在系統設定的「已連結的裝置」，以及解除 association 或 MoonClicker 重裝時 VD 的生命週期。
- 只在 `separate_timeouts` 開啟的版本改用這條路徑；其他版本維持 `OWN_DISPLAY_GROUP`。
