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
| `api36` | `aosp-atd` | Tier 0 + Tier 1 step1–5 | 開機快，平常跑 |
| `api36aosp` | `aosp` | 全部，含 `vision.*` 比對 | 映像檔大、開機慢 |

step6 在比不中的時候會先量一次「puppet 明明在畫面上，抓下來的影格有幾種顏色」，只有一種
就 `Assume` 跳過。**量的是性質不是裝置名**：`Build.PRODUCT` 裡有沒有 "atd" 是 proxy，
會隨映像檔改名而腐爛，而「影格是不是全同色」就是我們真正在意的那件事。所以在 `api36` 上
比對會被跳過，在 `api36aosp` 與實機上會真的跑。

### 量到的事實

`api36aosp` 30/30（0 skipped）、SM-A217F / Android 12 30/30、`api36` 29 綠 + 比對跳過。
trusted 與非 trusted 兩條顯示器路徑各有一台涵蓋到。過程中量到的：

- **不是每台裝置的 shell 都有 `ADD_TRUSTED_DISPLAY`。** SM-A217F 沒有，Pixel 7a (API 37)
  有。production 原本從 API 31 起無條件加上 `VIRTUAL_DISPLAY_FLAG_TRUSTED`，在前者上會直接
  `SecurityException`——整台不能用。現在改成拿不到就退回非 trusted 重試
  （`RelcV2Service.createDisplay`）。**退回之後注入觸控仍然works**，這台上實測過。
- **Android 12 的 splash screen 會在 activity 都 resume、也畫完之後還壓著一陣子。**
  那段期間注入的觸控收不到，而且視窗幾何還在變——早期擠進來的那一下座標會對不上。
  所以 `tapUntilReceived` 每輪都先清掉記錄再注入，量的是穩定之後的狀態。
- **`Canvas.drawBitmap(bmp, x, y, paint)` 會做密度縮放。** bitmap 帶的是預設顯示器的密度，
  canvas 目標是虛擬顯示器的，兩者不同時標記就不是你以為的尺寸，而 `TM_CCOEFF_NORMED`
  不是尺度不變的。指定目的矩形強制 1:1。（這是 puppet 的 bug，不是產品的——但它是任何人
  寫這種測試都會踩到的那一個。）

### 還沒做的

- 只跑過 API 31 與 36。中間那幾級（尤其 27–29 沒有 TRUSTED 旗標可用）未知。
- `vision.wait` 的「等到它出現」語意還沒真的被驗——puppet 的畫面是靜態的，比對第一幀就中。
  要驗等待，puppet 需要能排程「N 毫秒後換一個圖樣」。

### 旋轉（step7/step8）

轉顯示器要**讓 puppet 自己宣告方向**，不是從外面呼叫 `setDisplayRotation`。後者是設 user
rotation，而 app 宣告的方向會贏過它——實測在 SM-A217F 上那樣轉不動，`freezeDisplayRotation`
回 true 但顯示器仍是 720x1280。這正是 CONTEXT.md「方向鏈」`Y → VD → X → MainDisplay`
的第一環：**顯示器裡的 app 決定顯示器的方向**。

兩個踩過的坑：

- **模板必須旋轉對稱。** 影格在 surface 空間，顯示器轉 90 度時內容是被轉「進」緩衝區的，
  而 `TM_CCOEFF_NORMED` 不是旋轉不變的。原本的棋盤格轉 90 度會反相，於是比不中。改成同心
  方框。另一條路是「測試自己把模板也轉 90 度」，但那會把 surface 空間的旋轉方向寫死進
  測試——方向猜錯時人會傾向一直翻到綠為止，而那正是這個測試該抓的東西。用對稱圖樣就沒有
  這個誘惑，**位置**成為唯一被斷言的東西，而位置是 puppet 獨立回報的。
- **先確認「真的轉了」再談座標。** 旋轉沒生效與換算錯誤，症狀都是「點沒打中」，但要查的
  地方完全不同。所以先等 puppet 的 content 長寬互換，再往下。

另外 step6/7/8 都會斷言腳本看到的 `screen.width/height` 跟 puppet 實際被排版的尺寸一致。
這條把 ADR-0012 的兩段交接釘住（`nativeStart` 帶進去的初始 rotation ＋
`DisplayRotationTracker` 後續推的更新），少了它，往返即使通過也只是「推論」native 知道
自己轉了。

## 不測什麼

平台本身的假設不在這裡——那是 `:hidden-api-contract` 的 API 矩陣在做的事。這裡只有一個
API level，因為驗的是 Lua 綁定，不是 Android。

[Script Folder]: ../CONTEXT.md
