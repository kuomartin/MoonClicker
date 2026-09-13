# 虛擬顯示的坑

實測踩出來的平台行為，以及它們各自逼出了哪一段程式碼。程式碼裡只留「不能怎麼寫」，
出處和量到的數字放這裡。

除非另外註明，測量環境是 **Samsung SM-A217F（Android 12 / API 31）** 與
**Gradle managed device api27–36**。

---

## 平台

### 不是每台裝置的 shell 都有 `ADD_TRUSTED_DISPLAY`

SM-A217F 沒有，Pixel 7a（API 37）有。而 Shizuku 就跑在 shell 身分上。

ReLC 原本從 API 31 起無條件加上 `VIRTUAL_DISPLAY_FLAG_TRUSTED`，於是在前者上每次
`createVirtualDisplay` 都是 `SecurityException: Requires ADD_TRUSTED_DISPLAY permission`
——整台裝置建不出任何虛擬顯示。

界線改到 **API 33**（與 scrcpy 的 `NewDisplayCapture` 一致：shell 是從 Android 13 才被授予
那個權限），並且改成**直接問** `checkSelfPermission` 而不是靠例外試錯。

→ `RelcV2Service.ADD_FLAGS_33`、`RelcV2Service.privilegedFlags()`

### 那組旗標不是一包，是三條獨立檢查

`DisplayManagerService.createVirtualDisplayInternal`：

| 旗標 | 前提 | 不符時 |
|---|---|---|
| `TRUSTED` | `ADD_TRUSTED_DISPLAY` | SecurityException |
| `OWN_DISPLAY_GROUP` | `ADD_TRUSTED_DISPLAY`（自己一條檢查） | SecurityException |
| `ALWAYS_UNLOCKED` | **`ADD_ALWAYS_UNLOCKED_DISPLAY`**（不同的權限） | SecurityException |
| `OWN_FOCUS` | javadoc：display 必須 trusted | DMS 未檢查 |
| `DEVICE_DISPLAY_GROUP` | 與 `TRUSTED` 並用才生效 | 靜默 |
| `TOUCH_FEEDBACK_DISABLED` | 無 | — |

綁成一包丟掉的代價：有 `ADD_TRUSTED_DISPLAY` 卻沒有 `ADD_ALWAYS_UNLOCKED_DISPLAY` 的機器，
會為一個旗標賠掉整個 trusted 顯示器。而 `ALWAYS_UNLOCKED` 決定的是**鎖屏時虛擬顯示還收不
收得到觸控**。

→ `RelcV2Service.privilegedFlags()`、`Tier1SpikeTest.step2b`

### 沒有 `ALWAYS_UNLOCKED` 的顯示器，在裝置休眠時不派送觸控

裝置進入 `Dozing` 或停在 keyguard 時，注入到虛擬顯示的觸控會靜靜消失。症狀跟座標算錯、
權限不足、顯示器沒建起來完全一樣。

→ `Tier1Env.wakeAndUnlock()`

### API 27–28 注入不到虛擬顯示

`MotionEvent.setDisplayId` 是 API 29 才有的隱藏 API，在那之前沒有辦法把事件標到某個顯示器
上。`RelcV2Service.injectMotionEvent` 對非 0 的 displayId 直接回 false。**產品的能力邊界。**

Tier 1 觀察不到它：`UiAutomation.adoptShellPermissionIdentity` 也是 API 29（27/28 實測
`NoSuchMethodError`），兩個下限剛好重合。曾經為此加過一個能力閘門，在可達範圍內恆為真，
已拆除。

### API 31 模擬器不讓 app 宣告的方向傳到虛擬顯示

**原因未定。** 已排除：auto-rotate 是開的（`accelerometer_rotation=1`）；不是版本問題
（同為 API 31 的 SM-A217F 會跟隨，30/33/34/35 也會）。最大嫌疑是
`ignoreOrientationRequest`（API 31 引進的 per-display 設定）。

`Tier1SpikeTest` 的旋轉步驟在轉不動的環境下 assume 掉，訊息裡帶著
`dumpsys window displays` 供下一個人接手。

### 轉虛擬顯示要讓裡面的 app 宣告方向

從外面呼叫 `setDisplayRotation`（`freezeDisplayRotation`）是設 user rotation，而 app 宣告的
方向會贏過它——SM-A217F 上回傳 true 但顯示器仍是 720×1280。這正是 CONTEXT.md「方向鏈」
`Y → VD → FullscreenDisplayActivity → MainDisplay` 的第一環。

→ `PuppetActivity.requestOrientation()`

### ATD 系統映像檔沒有圖形堆疊

虛擬顯示建得起來、`GlesDistributor` 照樣送影格——但每一張都是全黑（在 Android Studio 裡開
那台模擬器看到的也是黑畫面）。所以 `:engine` 有兩台同 API level 的受管理裝置，差別只在
映像檔。

判斷方式是**量性質不是認裝置名**：`Build.PRODUCT` 含 "atd" 是 proxy，會隨映像檔改名腐爛。

→ `Tier1Env.distinctColorsOnDisplay()`

---

## 我們自己的程式碼

### `AImageReader_delete` 會跟執行中的回呼互鎖

`ScriptEngine.stop()` 曾經永遠不返回，也就是 App 裡按「停止」會凍住。兩執行緒互等：

```
拆除端   AImageReader_delete → close() → ALooper::stop → Thread::requestExitAndWait
                                                          ← 等回呼執行緒退出
回呼端   ALooper::loop → onImageAvailable → AImage_delete → MutexLockWithTimeout
                                                          ← 等同一把內部鎖
```

60fps 之下「拆除當下剛好有影格在處理」是機率事件，所以間歇發生，約每五、六次一次。

正確順序：**拔 listener → 等在途回呼做完 → 才刪**。只清掉自己的 `std::function` 不夠——
native listener 還註冊著，回呼照樣進來、照樣呼叫 `AImage_delete`。

同一顆地雷在 Kotlin 端也有：`ImageReader.close()` 時回呼還在讀 buffer，會是
`IllegalStateException: buffer is inaccessible` 或 `Image is already closed`。

→ `NativeImageReader::release()`、`Tier1Env.sampleFrames()`

### `data.set(key, table)` 會把整份腳本帶走

`boxLuaValue` 從 `_G.cjson` 取編碼器，但 cjson 是用 `luaL_requiref(..., glb = 0)` 載入的，
而它自己註冊全域的那段被 `ENABLE_CJSON_GLOBAL` 編譯掉了——`_G.cjson` 是 nil，`lua_getfield`
當場拋錯。`docs/lua-api.md` 明文承諾 table 會以 JSON 過橋。

→ `ScriptRuntime::boxLuaValue()`（改走 `package.loaded`）

### `com.android.shell` 被寫死成呼叫者身分

在 `fakeDisplayContext` 與 `Workaround.startActivity` 的三個分支裡。system_server 會拿它跟
calling uid 對，所以只在 Shizuku 起的 shell 進程裡成立。

→ `RelcV2Service(context, callerPackage = ...)`

### 分發器閒置時仍以 100Hz 空轉

`frameCond.wait_for(lock, 10ms)` 沒有 predicate，每次都逾時醒來。`frameAvailable` /
`onFrameAvailable` / `frameCond` 是一組**從沒接上**的事件驅動骨架：listener 沒註冊、旗標
沒人寫、condvar 沒人 notify。改成條件式等待會直接睡死。

現在依有沒有 sink 決定節奏（10ms / 250ms）。閒置時仍需定期 `updateTexImage` 排空，否則
SurfaceTexture 的佇列滿了會擋住生產端。

→ `GlesDistributor::renderLoop()`

---

## 測試才會遇到的

### 環境前提會偽裝成產品 bug

同一個模式出現四次，症狀都是某個斷言失敗、看起來像座標或權限錯了：

| 真正的原因 | 偽裝成 | 對策 |
|---|---|---|
| ATD 影格全黑 | 比對不中 | `distinctColorsOnDisplay` |
| Android 12 splash 還壓在已 resume 的視窗上 | 觸控沒送達 | `tapUntilInside` 重試 |
| 裝置 Dozing／鎖屏 | 觸控沒送達 | `wakeAndUnlock` |
| 啟動／旋轉動畫未結束 | 座標算錯 | `awaitStableFrame` |

前三次都是再補一個**代理條件**；第四次才改成量真正在意的性質——**連續幾張影格取樣相同**
——而那一個條件回頭涵蓋了前面三種。

動畫期間的證據：注入 y=506，收到 334.01 與 369.01，x 精確不變、y 各差一個純縮放
（1.515 與 1.371）。旋轉動畫則是 `vision` 以 **confidence 1.0** 命中一個過渡位置。

### 模板必須旋轉對稱，測試才問得出座標

影格在 surface 空間，顯示器轉 90° 時內容被轉「進」緩衝區，而 `TM_CCOEFF_NORMED` 不是旋轉
不變的。棋盤格轉 90° 會反相，於是比不中。

另一條路是「測試自己把模板也轉 90°」，但那會把 surface 空間的旋轉方向寫死進測試——方向猜
錯時人會傾向一直翻到綠為止，而那正是這個測試該抓的東西。

代價是對稱圖樣**對方向完全盲目**，所以另外加一個不對稱的 Γ 字形。兩個一起看，失敗才可讀：
「兩個都沒中」＝ 環境或座標；「只有不對稱的沒中」＝ 方向。

→ `PuppetMarker`（對稱）、`PuppetGlyph`（不對稱）

### `Canvas.drawBitmap(bmp, x, y, paint)` 會做密度縮放

bitmap 帶的是預設顯示器的密度，canvas 目標是虛擬顯示器的，兩者不同時圖樣就不是你以為的
尺寸——而 `TM_CCOEFF_NORMED` 不是尺度不變的。指定目的矩形強制 1:1。

### `confidence` 在 miss 時不可讀

`VisionMatcher::match` 低於門檻就 `continue`，留下預設的 0。所以「看分數判斷是全黑還是縮放」
行不通。

### `@After` 會在 setUp 的 assumption 失敗後照樣執行

`env` 沒初始化就 `close()`，`UninitializedPropertyAccessException` 把**跳過偽裝成失敗**。
第一次跑 API 27 時報「9 個失敗、skipped=0」就是這個。

### 綠燈不等於有鑑別力

兩次差點收下沒有鑑別力的綠燈：

- **旋轉方向**：step7/8 原本只用旋轉對稱的圖樣，而對稱圖樣轉 90° 還是自己——方向寫反也全綠。
  加上不對稱的 Γ 字形之後，先確認它在改動前是紅的（rotation 1/3 `found=false`、rotation 0
  通過），才動實作。
- **`vision.wait` 的等待**：`elapsed >= 2000` 有可能只是撞到「腳本啟動＋拆除本來就要 2 秒」
  的固定成本，那樣斷言就是恆真。把延遲拉到 6 秒重跑仍然通過，才確認 elapsed 跟著延遲走。

共同的教訓：**先讓測試因為對的理由紅一次**，綠燈才有意義。

---

## 未解

- **API 31 模擬器的方向請求**（見上）。
- **`multi_swipe_dispatches_every_pointer` 在 API 31 模擬器上是沒有訊息的裸 `<skipped/>`。**
  它屬於 Tier 0、不依賴環境，其他五級都正常。沒有追出原因。
