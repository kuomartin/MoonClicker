# VD 橫向 App 沒填滿畫面：根因與修法（已真機確認）

## 結論

`DisplayManager.createVirtualDisplay()` 建立 VD 時沒帶 `VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`，是橫向 App 在這個 VD 上啟動後畫面沒填滿（黑邊、系統 letterbox 還原按鈕）的根因。修法是在建立 VD 時加上這個 flag，程式碼落在 [`DisplaysViewModel.kt`](../../app/src/main/java/com/xaxaxax/moonclicker/ui/displays/DisplaysViewModel.kt) 的 `defaultConfig`（`VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` 已經在 [`MoonClickerService.SUPPORTED_FLAGS`](../../engine/src/main/java/com/xaxaxax/moonclicker/MoonClickerService.kt) 裡，呼叫端本來就可以直接要）。

## 症狀

橫向 Unity 遊戲（`com.noodlecake.altosadventure`）透過 `setLaunchDisplayId()` 啟動到 MoonClicker 自建的 VD 上後，畫面只佔滿一小塊、貼齊左右置中但頂在畫面上方，下方一大片黑；VD 右下角出現系統原生的「letterbox 還原」按鈕（Android 12+ size-compat restart 按鈕），手動點擊後畫面立刻正確填滿橫向。

## 根因鏈（`adb shell dumpsys activity activities` 實測佐證）

沒有 `SHOULD_SHOW_SYSTEM_DECORATIONS` 時，該 Activity 第一次上報的 `mLastReportedConfigurations.mGlobalConfig`：

```
mDisplayRotation=ROTATION_0
mBounds=Rect(0, 0 - 1080, 486)
```

`1080` 是 VD 建立時的直向寬度（轉向前），`486 = 1080 / (2400/1080)`——`2400/1080 ≈ 2.222` 正是 App 橫向內容的長寬比。也就是說 WMS 在算這個 Activity 的初始 compat bounds 時，拿「App 想要的長寬比」硬套進「顯示器還沒轉向前的直向寬度」，算出一個兩邊都對不上的尺寸，判定「App 不支援目前尺寸」，觸發：

```
areBoundsLetterboxed=true
isLetterboxRunning=true
letterboxReason=SIZE_COMPAT_MODE
letterboxVerticalPositionMultiplier=0.0   ← 貼頂，對應畫面「內容卡在上方」
```

`SIZE_COMPAT_MODE` 一旦凍結，之後 VD 真的轉向橫向（`ROTATES_WITH_CONTENT` 正常運作）也不會讓已凍結的 Activity 重新計算，只能靠使用者手動點系統按鈕清掉凍結狀態。

### 排除過的假說（不要重踩）

- **`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 沒帶到**：兩邊都有，不是差異點。
- **`setIgnoreOrientationRequest`**：AOSP 全新 VD 上這個值預設就是 `false`，scrcpy 也從未呼叫過，跟這次的症狀無關。
- **`ActivityOptions.setLaunchBounds()`**：真機實測完全無效——`FULLSCREEN` windowingMode 的 Task 會直接忽略這個 hint，算出來的 `mGlobalConfig` 跟不帶時逐位元相同。
- **啟動時序（VD 建完立刻啟動 App，中間沒有等待）**：查證排除——scrcpy 的 `waitDisplayData()` 等的是另一條執行緒回報 displayId 這件事本身，MoonClicker 的 `createVirtualDisplay()` 是同步 binder call，回傳時 displayId 已經保證存在，沒有 scrcpy 在等的那個窗口。

### 找到差異的方法

逐一比對兩邊 VD 的 `adb shell dumpsys display` 裡 `DisplayDeviceInfo` 的 flags 列表（而不是繼續啃 AOSP 原始碼）：

```
scrcpy:      ROTATES_WITH_CONTENT, PRESENTATION, OWN_CONTENT_ONLY, DESTROY_CONTENT_ON_REMOVAL,
             SHOULD_SHOW_SYSTEM_DECORATIONS, TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED,
             TOUCH_FEEDBACK_DISABLED, OWN_FOCUS
MoonClicker: ROTATES_WITH_CONTENT, PRESENTATION, OWN_CONTENT_ONLY,
             TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED,
             TOUCH_FEEDBACK_DISABLED, OWN_FOCUS
```

差 `SHOULD_SHOW_SYSTEM_DECORATIONS`（跟生命週期用的 `DESTROY_CONTENT_ON_REMOVAL` 無關，那個純粹是 VD 移除時清 content，跟 orientation 無關）。加上這個 flag 重測，`mGlobalConfig` 第一次上報就已經是：

```
mDisplayRotation=ROTATION_90
mBounds=Rect(0, 0 - 2400, 1080)   ← 滿版
areBoundsLetterboxed=false
isLetterboxRunning=false
```

跟 scrcpy 的乾淨結果一致。

## 為什麼這個 flag 有差

`SHOULD_SHOW_SYSTEM_DECORATIONS` 讓 WMS 把這台 VD 當成「有系統裝飾的正常顯示器」（狀態列、手勢區、完整 `DisplayPolicy`）處理，而不是陽春的內容鏡射顯示器。少了它，這台 VD 在 Activity 建立那一刻的方向/尺寸解析走的是另一條精簡路徑，沒能在算初始 compat bounds 前把 `ROTATES_WITH_CONTENT` 的轉向結果同步進來。

## 前置研究（已被本篇結論取代或補完的部分）

- [`scrcpy-new-display-vs-am-start-orientation.md`](scrcpy-new-display-vs-am-start-orientation.md)：只查了 `ROTATES_WITH_CONTENT`，結論不完整。
- [`scrcpy-vs-moonclicker-ignore-orientation-request.md`](scrcpy-vs-moonclicker-ignore-orientation-request.md)：排除 `ignoreOrientationRequest`、指出根因是 Activity 層級的 Size Compat Mode，方向正確但沒找到 flag 差異。
- [`moonclicker-scm-initial-bounds-race.md`](moonclicker-scm-initial-bounds-race.md)：排除了啟動時序假說，正確判斷純讀原始碼查不出 `1080x486` 的來源，建議真機比對——本篇是那次比對的結果。
