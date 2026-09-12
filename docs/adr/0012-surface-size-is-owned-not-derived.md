# Surface 尺寸由服務擁有，不由呼叫端回推

`IRelcV2Service.getDisplaySize` 回的是 `Display.getRealSize()`，也就是**邏輯**尺寸——旋轉
90/270 時長寬互換。但 `AImageReader` 必須以虛擬顯示**建立時**的 surface 尺寸開。兩者差一個
直角，所以 `ScriptEngine.start` 原本這樣回推：

```kotlin
val size = service.getDisplaySize(displayId)          // 跨進程，邏輯尺寸
val rotation = displayManager.getDisplay(displayId).rotation  // 本進程，另一次讀取
val (w, h) = DisplayGeometry.surfaceSize(size[0], size[1], rotation)
```

這是兩次**獨立**的讀取，中間沒有共同的時間點。畫面在這兩行之間轉了，`surfaceSize` 會拿到
一組看起來很合理、實際上不自洽的輸入，然後算出一個錯得很有自信的答案。

危害是永久的而非短暫的：`surfaceWidth/Height` 在 `ScriptRuntime::start` 用來開
`AImageReader` 與建 `VisionMatcher`，整場執行只設定一次。rotation 相對之下會自己修正——
`DisplayRotationTracker` 會持續推新值進去——但影格緩衝區的長寬比錯了就一路錯到腳本結束。

## 決定

surface 尺寸是虛擬顯示**建立時的常數**，服務在 `createVirtualDisplay` 當下就知道它。與其
讓呼叫端從兩個活的讀數把它重建出來，不如讓知道的人記住它：

- `vdStore` 的值從 `VirtualDisplay` 換成 `ManagedDisplay(display, surfaceWidth, surfaceHeight)`。
- 新增 `getDisplaySurfaceSize(displayId) = 302`，虛擬顯示直接回存下來的建立尺寸，**完全不經過推導**。
- display 0 沒有「建立尺寸」可言，只能推；但邏輯尺寸與 rotation 都取自服務進程裡的同一個
  `DisplayManager`，沒有跨進程的時間差。
- 其他 id 回 `[0, 0]`，呼叫端當場失敗。服務沒建過的顯示器，我們沒有立場猜它的幾何——
  帶著可能錯的尺寸跑完整場，比在啟動時明確失敗糟得多。這也順帶讓「服務重綁之後才拿出來用的
  舊 displayId」這種錯誤現形。

`DisplayGeometry.surfaceSize` 與它的測試留著：display 0 那條路仍然需要它，而 `:app` 的
鏡像與 `vision.*` 也還在做邏輯↔surface 的換算。變的是**啟動路徑不再猜**。

## 沒有新測試，這是刻意的

這個候選項原本的賣點是「讓那個競態可以被測出來」。實際做法把推導整個刪掉，於是沒有競態
可測了——結構上消失，不是靠斷言守住。為了測一個純轉手的呼叫而生出一個介面，那個介面的
存在理由就只剩它自己的測試，反而是更淺的模組。

## 名字與旋轉不記在這裡

- **名字**：`Display.getName()` 原樣回傳建立時給的名字（實機 `dumpsys display` 驗證：
  `DisplayInfo{"ReLC", displayId 12, ...}`，被加前綴的是 `uniqueId` 而不是 name）。
  平台已經是它的擁有者，記第二份只是複製。
- **旋轉**：它是**活的狀態**，不是建立時的常數。顯示器裡的 app 宣告方向時 WindowManager
  會直接轉它，服務不會被告知——`setDisplayRotation` 的文件本身就寫著 app 宣告的方向會贏、
  這是預期行為。服務記下來的會是一個**請求**而不是現況，而這正是本 ADR 要消滅的那種
  「一個事實兩個來源」。旋轉維持由 `DisplayRotationTracker` 活體觀察。

## rotation 讀兩次是對的，不要合併

`ScriptEngine.start` 讀一次 rotation 隨 `nativeStart` 傳進去，`DisplayRotationTracker.start`
接著又讀一次推進 native。看起來像重複，其實兩段都在做事：

1. **`initialRotation`**：`ScriptRuntime::start` 的最後一件事就是 `luaThread = std::thread(...)`，
   所以 `nativeStart` 一回到 Kotlin，腳本可能已經在跑了。這是腳本第一行讀 `screen.width`
   之前的最後一個時機。順序也不能對調——`nativeSetDisplayRotation` 在 `nativeStart` 之前是
   空操作（`if (gRuntime)` 擋著，且 `VisionMatcher` 尚未建立）。
2. **tracker 的先行讀取**：從上面那個快照到 listener 真正註冊完之間還有一段空窗，期間發生的
   旋轉不會有任何 `onDisplayChanged` 補上，只能靠這裡再讀一次收掉。

記在這裡是因為它會被重複「發現」：2026-09 的架構檢視就把它當成可以合併的重複回報過一次，
而唯一擋下來的理由（Lua 執行緒在 `ScriptRuntime::start` 內部就起跑）當時只存在於一行註解裡。

## Status

Accepted。補充 [ADR-0006](0006-module-split-and-engine-facade.md)：`:engine` 是碰原生內部的
唯一邊界，本 ADR 進一步指明在那個邊界內，顯示器建立時的常數由 `RelcV2Service` 擁有。
與 [ADR-0011](0011-scripts-do-not-own-displays.md) 一致——腳本不擁有顯示器，也就不該是
知道它幾何的人。

## 已知的同類問題（另案處理）

`ScriptSession.matchesSize` 用邏輯尺寸比對要沿用的顯示器，並且「兩種擺法都算符合」來繞過
同一個長寬互換問題。建立尺寸現在拿得到了，那個模糊比對可以收緊，但它會改變使用者觀察得到的
顯示器沿用行為，血緣不同，另開 issue。
