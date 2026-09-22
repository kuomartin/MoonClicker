# Fullscreen fab menu 新增 display power off

`wakeDisplayGroupIfOwned`（`MoonClickerService.kt:674`）的逆操作：把 VD 的 display group 關掉（DPMS off），不是銷毀 VD。AOSP 對應 API 是 `PowerManager.goToSleep(int displayId, long time, int reason, int flags)`（`android-17.0.0_r1` 已穩定、無 `@FlaggedApi`，簽章與 `DEVICE_POWER` 權限跟現有 `wakeUp` 同層級），底層呼叫 `mService.goToSleepWithDisplayId(...)`。

## A. 新增/改動的檔案

| 檔案 | 內容 |
|---|---|
| `hidden-api/src/main/java/android/os/PowerManagerHidden.java` | 新增常數 `GO_TO_SLEEP_REASON_APPLICATION = 0`；新增 stub `public void goToSleep(int displayId, long time, int reason, int flags)` |
| `IMoonClickerService.aidl` | 新增 `boolean sleepVirtualDisplay(int displayId) = 113;`（VirtualDisplay 管理區段，接在 112 後面） |
| `MoonClickerService.kt` | 實作 `override fun sleepVirtualDisplay(displayId: Int): Boolean`：比照 `wakeDisplayGroupIfOwned` 只認 `vdStore.containsKey(displayId)` 的自建 VD，呼叫 `PowerManagerHidden.goToSleep(displayId, SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_APPLICATION, 0)` |
| `RecordingMoonClickerService.kt`（androidTest 測試替身） | 補 `override fun sleepVirtualDisplay(displayId: Int): Boolean = unused("sleepVirtualDisplay")` |
| `FullscreenDisplayViewModel.kt` | `FullscreenAction` 新增 `data object PowerOff`；`onAction` 分派到新的 `sleepDisplay(displayId)`（呼叫 `service.sleepVirtualDisplay`，失敗僅 log，不 finish activity） |
| `FullscreenDisplayActivity.kt` | `fanActions` 新增一顆 `FanMenuAction(Icons.Default.PowerSettingsNew, powerOffLabel) { viewModel.onAction(FullscreenAction.PowerOff, targetDisplayId) }`，跟 `CloseDisplay` 一樣只在 `targetDisplayId != 0` 時加入 |
| `values/strings.xml` / `values-zh-rTW/strings.xml` | 新增 `fullscreen_menu_power_off`（"Power Off Display" / "關閉螢幕電源"） |

## B. 已定案

1. **不影響 VD 本身**：這是螢幕電源狀態（DPMS off），不是 `destroyVirtualDisplay`——VD、distributor、鏡像連線都留著，只是該 display group 進入 sleep。跟現有 `CloseDisplay` 是兩個獨立按鈕。
2. **只對本服務自建的 VD 生效**：沿用 `wakeDisplayGroupIfOwned` 的 `vdStore.containsKey` 檢查，不對主螢幕或非本服務建立的顯示器呼叫這支 API。
3. **點下去之後畫面不會自動關閉/離開全螢幕**：螢幕電源關閉後鏡像多半變黑，使用者要嘛用 Exit 離開、要嘛之後點別的動作（`launchInDisplay`/`addVirtualDisplaySurface` 等既有呼叫點都已經有 `wakeDisplayGroupIfOwned`）自動喚醒。
4. **reason 用 `GO_TO_SLEEP_REASON_APPLICATION`（值 0）**，跟 `wakeUp` 用 `WAKE_REASON_APPLICATION` 對稱；`flags` 傳 `0`。
