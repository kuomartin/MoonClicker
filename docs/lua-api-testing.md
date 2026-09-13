# Lua API 怎麼測

`docs/lua-api.md` 是對腳本作者的承諾。這份講那些承諾怎麼變成會失敗的測試。

## 支點是 `IRelcV2Service`

Lua API 看起來很難測——它要 Shizuku、要虛擬顯示、要注入權限。但這些東西**全部**都在
`IRelcV2Service` 後面。從 `main.lua` 到那個介面之間的每一段：

```
main.lua → LuaBindings.cpp（參數解析、座標攤平）
         → ScriptRuntime.cpp（執行緒、停止旗標、值裝箱）
         → ScriptHost.kt（pointer 狀態機、KeyEvent 組裝）
         → IRelcV2Service   ← 這裡是邊界
```

換掉那一個介面，上面三層就能在**沒有 Shizuku、沒有虛擬顯示、沒有特殊權限**的乾淨模擬器上
跑完。這正是 Tier 0 在做的事。

## Tier 0 —— 已完成，`engine/src/androidTest`

```bash
./gradlew :engine:api36DebugAndroidTest      # 受管理的模擬器，會自動建
./gradlew :engine:connectedDebugAndroidTest  # 已連線的裝置
```

`RecordingRelcService` 是一個把呼叫記下來、而不是真的執行的 `IRelcV2Service.Stub()`。
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

第一次跑就抓到一個 bug：`data.set(key, table)` 會**整份腳本一起帶走**——`boxLuaValue`
從 `_G.cjson` 取編碼器，但 cjson 是用 `luaL_requiref(..., glb = 0)` 載入的，而它自己註冊
全域的那段又被 `ENABLE_CJSON_GLOBAL` 關掉了。已改成走 `package.loaded`。

### 這一層驗不到的

`RecordingRelcService` 立刻返回，真實服務的 `multiTouchSwipe` 是 `oneway` 而且會依
duration 插值。所以 Tier 0 能說「引擎送出了什麼」，不能說「螢幕上發生了什麼」。
`vision.*` 的比對本身（OpenCV、surface→邏輯座標的實際換算）也不在這裡。

## Tier 1 —— 已驗證可行，`Tier1SpikeTest`

一個真的顯示器 + 一個真的會反應的 app，用來驗「點下去真的點到了」「`vision.find` 真的在
畫面上找到那張圖」。

```bash
ANDROID_SERIAL=<serial> ./gradlew :engine:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.xaxaxax.relc.script.Tier1SpikeTest
```

### 沒有 Shizuku 也拿得到 shell 身分

`UiAutomation.adoptShellPermissionIdentity()` 讓權限檢查以 shell 身分進行——而 Shizuku
給 ReLC 的正是 shell 身分。所以測試在自己的進程裡直接 `RelcV2Service(context)`，用的是
**真的服務**，不是替身；`RelcV2Service` 也因此第一次有了覆蓋。

換宿主進程要自己補兩件事，都是 shell 進程「免費」拿到的：

| | 為什麼 | 怎麼補 |
|---|---|---|
| 隱藏 API 豁免 | shell uid 整個免受名單約束，app uid 不是 | `Tier1Env.exemptHiddenApis()` |
| 呼叫者套件名 | system_server 拿它跟 calling uid 對 | `RelcV2Service(context, callerPackage = ...)` |

第二項是這個 spike 逼出來的 production 改動：`com.android.shell` 原本寫死在
`fakeDisplayContext` 與 `Workaround.startActivity` 三個分支裡。現在是建構子參數，
預設值不變，Shizuku 那條路 byte-identical。

### puppet 住在測試 APK 裡

library 的 androidTest APK 是自我 instrument 的，所以 `PuppetActivity` 與測試程式碼
**在同一個進程**——測試直接讀 `PuppetRecorder`，不需要 IPC、不需要第二個 APK 的安裝流程。
`app.launch("com.xaxaxax.relc.engine.test")` 就能把它拉上虛擬顯示。

斷言是**自洽**的：vision 說標記在哪、input 就打去哪、puppet 回報打到哪。中間任何一段座標
換算錯了都會露出來，而且不依賴 letterbox、density、insets 的任何假設。

### 兩台模擬器，差別是圖形堆疊不是 API level

ATD（automated test device）系統映像檔把圖形堆疊拿掉了。虛擬顯示照樣建得起來、
`GlesDistributor` 照樣以 60fps 送影格——但每一張都是全黑（在 Android Studio 裡開那台
模擬器看到的也是黑畫面）。所以受管理裝置有兩台，同一個 API level：

| | 映像檔 | 涵蓋 | 何時用 |
|---|---|---|---|
| `api36` | `aosp-atd` | Tier 0 + Tier 1 除了比對那幾步 | 開機快，平常跑 |
| `api36aosp` | `aosp` | 全部，含 `vision.*` 比對 | 映像檔大、開機慢 |

step6/7/8 共用的那一圈在比不中的時候會先量一次「puppet 明明在畫面上，抓下來的影格有幾種
顏色」，只有一種就 `Assume` 跳過。**量的是性質不是裝置名**：`Build.PRODUCT` 裡有沒有 "atd" 是 proxy，
會隨映像檔改名而腐爛，而「影格是不是全同色」就是我們真正在意的那件事。所以在 `api36` 上
比對會被跳過，在 `api36aosp` 與實機上會真的跑。

### 量到的事實

SM-A217F 31/31、`api36aosp` 31/31（0 skipped）。trusted 與非 trusted 兩條顯示器路徑各有一台
涵蓋到。過程中量到的：

- **不是每台裝置的 shell 都有 `ADD_TRUSTED_DISPLAY`。** SM-A217F（Android 12）沒有，
  Pixel 7a (API 37) 有。production 原本從 API 31 起無條件加上
  `VIRTUAL_DISPLAY_FLAG_TRUSTED`，在前者上會直接 `SecurityException`——整台建不出顯示器。
  現在那一組旗標從 **API 33** 起才給（與 scrcpy 的 `NewDisplayCapture` 同一條界線），
  另外保留「被擋下來就退回非 trusted 重試」當保險。
  **非 trusted 的顯示器上注入觸控仍然到得了 app**，這台上實測過。
- **那組旗標不是一包，是三條獨立檢查。** `TRUSTED` 與 `OWN_DISPLAY_GROUP` 各自要
  `ADD_TRUSTED_DISPLAY`；`ALWAYS_UNLOCKED` 要的是**另一個權限**
  `ADD_ALWAYS_UNLOCKED_DISPLAY`；`TOUCH_FEEDBACK_DISABLED` 沒有檢查。綁成一包丟掉的代價很
  具體：有 A 沒 B 的機器會為一個旗標賠掉整個 trusted 顯示器。step2b 逐項對照旗標與權限，
  因為**其餘測試全綠也分辨不出這件事**——非 trusted 顯示器一樣建得起來、一樣收得到觸控。
- **權限用問的，不要用丟例外試。** `checkSelfPermission` 查的是 `Process.myUid()`，在
  Shizuku 進程裡就是 shell，正是 system_server 會拿去對的身分。
- **`Canvas.drawBitmap(bmp, x, y, paint)` 會做密度縮放。** bitmap 帶的是預設顯示器的密度，
  canvas 目標是虛擬顯示器的，兩者不同時標記就不是你以為的尺寸，而 `TM_CCOEFF_NORMED`
  不是尺度不變的。指定目的矩形強制 1:1。（這是 puppet 的 bug，不是產品的——但它是任何人
  寫這種測試都會踩到的那一個。）

### 四次「環境前提偽裝成產品 bug」

同一個模式反覆出現，值得單獨列出來——症狀都是某個斷言失敗，看起來像座標或權限錯了：

| 真正的原因 | 偽裝成 | 怎麼處理 |
|---|---|---|
| ATD 映像檔沒有圖形堆疊，影格全黑 | 「比對不中」 | `distinctColorsOnDisplay`，只有一種顏色就跳過 |
| Android 12 的 splash 還壓在已 resume 的視窗上 | 「觸控沒送達」 | `tapUntilInside` 重試 |
| 裝置 Dozing／鎖屏，虛擬顯示不派送觸控 | 「觸控沒送達」 | `Tier1Env.wakeAndUnlock()` |
| 啟動／旋轉動畫還沒結束 | 「座標算錯」 | `awaitStableFrame` |

前三次我都是再補一個**代理條件**（等 resume、等 contentSize 換邊…）。第四次才改成量真正
在意的事：**連續幾張影格取樣相同**。那一個條件同時涵蓋前面三種動畫，不必各補一條。

`tapUntilInside` 的條件是「落點在目標裡」而不是「有收到」——視窗在動畫期間就收得到觸控，
但那時帶著縮放，回報的座標是過渡值（實測注入 y=506 收到 334.01 與 369.01，x 精確不變、
y 各差一個純縮放）。這不是「重試到過為止」：座標真的算錯就永遠不會落進目標，逾時後由
探針指出它一直落在哪裡。

寫這些取樣工具時我在 Kotlin 重寫了一次當天稍早才在 C++ 修掉的 bug——`ImageReader.close()`
時回呼還在讀 buffer。拆除順序（拔 sink → 等在途回呼 → 才 close）現在關在單一
`sampleFrames` 裡，兩個取樣函式共用。

### 旋轉（step7/step8）

轉顯示器要**讓 puppet 自己宣告方向**，不是從外面呼叫 `setDisplayRotation`。後者是設 user
rotation，而 app 宣告的方向會贏過它——實測在 SM-A217F 上那樣轉不動，`freezeDisplayRotation`
回 true 但顯示器仍是 720x1280。這正是 CONTEXT.md「方向鏈」`Y → VD → X → MainDisplay`
的第一環：**顯示器裡的 app 決定顯示器的方向**。

踩過的坑：

- **兩個圖樣，各驗一件事。** puppet 同時畫一個**旋轉對稱**的同心方框與一個**不對稱**的
  Γ 字形，step6/7/8 兩個都比。
  - 對稱的驗**座標**：它在任何角度都長一樣，所以比中與否只取決於 `frameToLogical` 對不對。
  - 不對稱的驗**方向**：模板是直立時截的，只有在引擎把模板轉到當前方向之後才會中
    （[ADR-0013](adr/0013-templates-are-logical-space.md)）。
  - 兩個一起看，失敗才可讀：「兩個都沒中」＝ 環境或座標；「只有不對稱的沒中」＝ 方向。
    這就是 ADR-0013 那個 bug 被抓出來的方式——只有對稱圖樣時 step7/8 全綠，方向錯了也看不見。
- **先確認「真的轉了」再談座標。** 旋轉沒生效與換算錯誤，症狀都是「點沒打中」，但要查的
  地方完全不同。所以先等 puppet 的 content 長寬互換，再往下。
- **`confidence` 在 miss 時不可讀。** `match()` 低於門檻就 `continue`，留下預設的 0——
  所以「看分數判斷是全黑還是縮放」行不通，要靠 `distinctColorsOnDisplay`。

另外 step6/7/8 都會斷言腳本看到的 `screen.width/height` 跟 puppet 實際被排版的尺寸一致。
這條把 ADR-0012 的兩段交接釘住（`nativeStart` 帶進去的初始 rotation ＋
`DisplayRotationTracker` 後續推的更新），少了它，往返即使通過也只是「推論」native 知道
自己轉了。

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

- **API 31 的模擬器不讓 app 宣告的方向傳到虛擬顯示**，所以旋轉那兩步在那裡被跳過。原因未定：
  auto-rotate 是開的（`accelerometer_rotation=1`），同樣 API 31 的實機會跟隨，30/33/34/35 也
  會，所以不是版本問題。最大嫌疑是 `ignoreOrientationRequest`（API 31 引進的 per-display
  設定），失敗訊息裡已經帶上 `dumpsys window displays` 供下一個人查。
- **`multi_swipe_dispatches_every_pointer` 在 API 31 模擬器上被跳過**，而且是沒有訊息的裸
  `<skipped/>`。它是 Tier 0、不依賴環境，其他五級都正常。沒有追出原因。
- `vision.wait` 的「等到它出現」語意還沒真的被驗——puppet 的畫面是靜態的，比對第一幀就中。
  要驗等待，puppet 需要能排程「N 毫秒後換一個圖樣」。

## 跟 `:hidden-api-contract` 的分工

兩邊都有 API 矩陣，問的問題不同：

- `:hidden-api-contract` 驗**平台**是否符合 `:hidden-api` 那些 stub 編碼的假設，測試 APK
  裡刻意不放 stub，讓平台成為唯一被載入的東西。
- 這裡驗 **ReLC 自己**在各版本上的行為：顯示器建不建得出來、旗標拿不拿得到、觸控派不派送、
  比對準不準。跨版本會變的是這些，不是 Lua 綁定本身（那是 Tier 0，一個 API level 就夠）。

[Script Folder]: ../CONTEXT.md
