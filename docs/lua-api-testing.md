# Lua API 怎麼測

`docs/lua-api.md` 是對腳本作者的承諾。這份講那些承諾怎麼變成會失敗的測試。

## 支點是 `IMoonClickerService`

Lua API 看起來很難測——它要 Shizuku、要虛擬顯示、要注入權限。但這些東西**全部**都在
`IMoonClickerService` 後面。從 `main.lua` 到那個介面之間的每一段：

```
main.lua → LuaBindings.cpp（參數解析、座標攤平）
         → ScriptRuntime.cpp（執行緒、停止旗標、值裝箱）
         → ScriptHost.kt（pointer 狀態機、KeyEvent 組裝）
         → IMoonClickerService   ← 這裡是邊界
```

換掉那一個介面，上面三層就能在**沒有 Shizuku、沒有虛擬顯示、沒有特殊權限**的乾淨模擬器上
跑完。這正是 Tier 0 在做的事。

## Tier 0 —— 已完成，`engine/src/androidTest`

```bash
./gradlew :engine:api36DebugAndroidTest      # 受管理的模擬器，會自動建
./gradlew :engine:connectedDebugAndroidTest  # 已連線的裝置
```

`RecordingMoonClickerService` 是一個把呼叫記下來、而不是真的執行的 `IMoonClickerService.Stub()`。
`LuaScriptRunner` 把一段 Lua 原始碼寫成 [Script Folder]、跑到終態、回傳結果。

斷言有三個管道，要驗什麼就挑哪個：

| 管道 | 驗的是 | 例 |
|---|---|---|
| `outcome.data` —— 腳本用 `data.set` 自己講 | 任何 Lua 看得見的事實 | `screen.width` 在旋轉後是多少 |
| `outcome.runState` | 這個呼叫該不該炸、停止是不是乾淨 | `vision.*` 在無影格目標上要報錯 |
| `outcome.calls` | 送到邊界上的實際值 | `input.swipe` 攤平後的座標順序 |

第一個管道是關鍵：**新增一個 Lua API 不需要新的測試管線**，讓腳本 `data.set` 出來就好。

涵蓋範圍：`input.*` 全部、`app.launch`、`device.*`、`data.set` 的每種型別、`screen.*`
（含旋轉時的長寬互換）、`require`、錯誤與 traceback、停止語意。

第一次跑就抓到一個 bug：`data.set(key, table)` 會整份腳本一起帶走
（見 [pitfalls](virtual-display-pitfalls.md)）。

### 這一層驗不到的

`RecordingMoonClickerService` 立刻返回，真實服務的 `multiTouchSwipe` 是 `oneway` 而且會依
duration 插值。所以 Tier 0 能說「引擎送出了什麼」，不能說「螢幕上發生了什麼」。
`vision.*` 的比對本身（OpenCV、surface→邏輯座標的實際換算）也不在這裡。

## Tier 1 —— 真的顯示器與真的 app

一個真的顯示器 + 一個真的會反應的 app，用來驗「點下去真的點到了」「`vision.find` 真的在
畫面上找到那張圖」。共用的環境是 JUnit rule `Tier1Env`。

| 類別 | 驗什麼 |
|---|---|
| `DisplayBringUpTest` | 特權旗標跟權限一致、distributor 送出影格、puppet 啟動並鋪滿顯示器 |
| `VisionCoordinatesTest` | 三個方向下，腳本看到的尺寸、`vision` 比中的位置、`roi` 的範圍 |
| `InputCoordinatesTest` | 三個方向下，注入的觸控落在瞄準的位置 |
| `VisionWaitTest` | `vision.wait`／`wait_any` 的等待語意 |
| `VirtualDisplayIdleDeadlockTest` | issue #6：注入的輸入喚醒睡著的 own display group |

```bash
ANDROID_SERIAL=<serial> ./gradlew :engine:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.xaxaxax.moonclicker.script.VisionCoordinatesTest
```

### 沒有 Shizuku 也拿得到 shell 身分

`UiAutomation.adoptShellPermissionIdentity()` 讓權限檢查以 shell 身分進行——而 Shizuku
給 MoonClicker 的正是 shell 身分。所以測試在自己的進程裡直接 `MoonClickerService(context)`，用的是
**真的服務**，不是替身；`MoonClickerService` 也因此第一次有了覆蓋。

換宿主進程要自己補兩件事，都是 shell 進程「免費」拿到的：

| | 為什麼 | 怎麼補 |
|---|---|---|
| 隱藏 API 豁免 | shell uid 整個免受名單約束，app uid 不是 | `LSPass.addHiddenApiExemptions`（`Tier1Env.before()`） |
| 呼叫者套件名 | system_server 拿它跟 calling uid 對 | `MoonClickerService(context, callerPackage = ...)` |

### puppet 住在測試 APK 裡

library 的 androidTest APK 是自我 instrument 的，所以 `PuppetActivity` 與測試程式碼
**在同一個進程**——測試直接讀 `PuppetRecorder`，不需要 IPC、不需要第二個 APK 的安裝流程。
`app.launch("com.xaxaxax.moonclicker.engine.test")` 就能把它拉上虛擬顯示。

斷言的對照組是 puppet **自己回報**的位置：vision 說標記在哪、input 打到哪，都各自跟 puppet
實際畫在哪、收到觸控在哪比。哪一段座標換算錯了就是哪個測試紅，而且不依賴 letterbox、
density、insets 的任何假設。

### 兩台模擬器，差別是圖形堆疊不是 API level

ATD（automated test device）系統映像檔把圖形堆疊拿掉了。虛擬顯示照樣建得起來、
`GlesDistributor` 照樣以 60fps 送影格——但每一張都是全黑（在 Android Studio 裡開那台
模擬器看到的也是黑畫面）。所以受管理裝置有兩台，同一個 API level：

| | 映像檔 | 涵蓋 | 何時用 |
|---|---|---|---|
| `api36` | `aosp-atd` | Tier 0 + Tier 1 除了比對那幾步 | 開機快，平常跑 |
| `api36aosp` | `aosp` | 全部，含 `vision.*` 比對 | 映像檔大、開機慢 |

vision 測試在比不中的時候會先量一次「puppet 明明在畫面上，抓下來的影格有幾種顏色」
（`Tier1Env.assumeFramesHaveContent`），只有一種就 `Assume` 跳過。**量的是性質不是裝置名**：`Build.PRODUCT` 裡有沒有 "atd" 是 proxy，
會隨映像檔改名而腐爛，而「影格是不是全同色」就是我們真正在意的那件事。所以在 `api36` 上
比對會被跳過，在 `api36aosp` 與實機上會真的跑。

### 踩過的坑

平台行為與我們自己的 bug 都收在 **[docs/virtual-display-pitfalls.md](virtual-display-pitfalls.md)**
——`ADD_TRUSTED_DISPLAY` 不是每台都有、旗標不是一包、`AImageReader` 的拆除順序、
環境前提偽裝成產品 bug 的四個案例等等。這裡不重複，程式碼註解也只留約束、把出處指過去。

### 旋轉（`VisionCoordinatesTest`、`InputCoordinatesTest`）

轉顯示器要**讓 puppet 自己宣告方向**，不是從外面呼叫 `setDisplayRotation`——後者是設 user
rotation，而 app 宣告的方向會贏過它。這正是 CONTEXT.md「方向鏈」
`Y → VD → FullscreenDisplayActivity → MainDisplay` 的第一環。

puppet 同時畫**旋轉對稱**的同心方框與**不對稱**的 Γ 字形，兩個各有一條測試：

- 對稱的驗**座標**——它在任何角度都長一樣，比中與否只取決於位置。
- 不對稱的驗**方向**——distributor 在源頭把影格轉正（[ADR-0017](adr/0017-vd-rotation-is-cancelled-at-the-distributor.md)），
  直立時截的模板在任何方向都該中；方向轉錯就漏。
- 兩條分開看，失敗才可讀：「兩個都沒中」＝ 環境或座標；「只有不對稱的沒中」＝ 方向。
  只有對稱圖樣的話，方向寫反也會全綠。

`screen_size_matches_the_puppet` 另外斷言腳本看到的 `screen.width/height` 跟 puppet 實際被
排版的尺寸一致，也就是 `getDisplaySurfaceSize` 在腳本啟動當下依 rotation 互換了長寬。

每條 vision 測試開始前都要等轉場真的結束（`Tier1Env.stage`），原因見
[pitfalls](virtual-display-pitfalls.md#轉場開始前畫面會先靜止一段)。

### `vision.wait` 的等待語意（`VisionWaitTest`）

其餘所有 vision 測試的畫面都是靜態的，比對第一幀就中——所以它們驗的其實是 `vision.find`，
`wait` 的等待從來沒被執行到。要問這件事，畫面必須在腳本**已經在等**的時候才改變，
於是 puppet 多了排程改變畫面的能力（`PuppetControl` 管輸入，`PuppetRecorder` 繼續只管輸出）。

| | 問的問題 | 怎麼安排 |
|---|---|---|
| `vision_wait_blocks_until_the_marker_appears` | `wait` 真的會等嗎 | 標記延後 2 秒才畫 |
| `vision_wait_returns_nil_on_timeout_without_erroring` | 逾時回 `nil` 還是拋錯 | 標記永遠不畫 |
| `vision_wait_any_reports_which_one_appeared` | `wait_any` 的 index 指的是出現的那一個嗎 | 只顯示兩個候選中的一個 |

逾時回 `nil` 是 `docs/lua-api.md` 明寫的承諾，腳本作者的錯誤處理全建立在它上面。`wait_any`
刻意只顯示一個——兩個同時出現的話 index 只反映呼叫順序。

鑑別力來自 `found`：標記在延遲前不在畫面上，「有比中」就蘊含「有等到」；時間斷言擋的是
「比中了畫面上別的東西」。**逾時那條是等待那條的反向對照**——少了它，一個永遠回傳 true 的
實作也會過。驗證過程見 [pitfalls](virtual-display-pitfalls.md#綠燈不等於有鑑別力)。

### API 矩陣

```bash
./gradlew :engine:api29DebugAndroidTest              # 單一級
./gradlew :engine:tier1MatrixGroupDebugAndroidTest   # 全部，慢，第一次要下載映像檔
```

全部用**有圖形堆疊**的映像檔（27–29 只有 `default` 有，30 起用 `aosp`），否則比對那一段會被
跳過，而跨版本要驗的正好包含它。

| API | 結果 |
|---|---|
| 27, 28 | Tier 0 全過；**Tier 1 跳過** |
| 29 | 31/31 —— Tier 1 能觸及的最舊一級，含 vision 與注入 |
| 30, 33, 34, 35, 36 | 31/31 |
| 31（模擬器） | 旋轉那兩步跳過，其餘全過 |
| 31（SM-A217F 實機） | 31/31 |

**Tier 1 的下限是 API 29**，因為它整個建立在 `UiAutomation.adoptShellPermissionIdentity` 上，
而那是 API 29 才有的（27/28 實測 `NoSuchMethodError`）。這是**測試框架**的限制，不是產品的：
production 在舊版上跑在 Shizuku 真正的 shell 進程裡，本來就不需要 adopt 任何身分。minSdk
仍然是 27，Tier 0 在那裡照常全過。

順帶一個巧合：`MotionEvent.setDisplayId` 也是 API 29 才有的，所以「注入不到虛擬顯示」這個
產品在 27/28 上的能力邊界，**Tier 1 永遠觀察不到**——兩個下限剛好重合。

### 還沒做的

兩項未解的觀察記在
[virtual-display-pitfalls.md 的「未解」](virtual-display-pitfalls.md#未解)。

## 跟 `:hidden-api-contract` 的分工

兩邊都有 API 矩陣，問的問題不同：

- `:hidden-api-contract` 驗**平台**是否符合 `:hidden-api` 那些 stub 編碼的假設，測試 APK
  裡刻意不放 stub，讓平台成為唯一被載入的東西。
- 這裡驗 **MoonClicker 自己**在各版本上的行為：顯示器建不建得出來、旗標拿不拿得到、觸控派不派送、
  比對準不準。跨版本會變的是這些，不是 Lua 綁定本身（那是 Tier 0，一個 API level 就夠）。

[Script Folder]: ../CONTEXT.md
