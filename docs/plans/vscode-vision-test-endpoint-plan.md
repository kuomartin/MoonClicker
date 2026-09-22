# 「測試模板」即時比對端點

**狀態**：已實作（B 方案）。屬於 [mirror 面板五步驟工作流程](../../vscode-extension/media/mirror.js) 的第 3 階段。

第 1 階段（「2 · 建立模板」的 ROI 裁切＋真的存檔）已實作：ROI 框改成跟「3 · 測試模板」同一套幾何算法的可拖曳 canvas（`vscode-extension/media/mirror.js` 的 `buildRoiCanvas`），「儲存模板」真的呼叫既有的 `PUT /scripts/{id}/templates/{name}`（走現有的 `saveTemplate` webview 訊息，`mirrorPanel.ts` 不用改）。

第 2 階段（模板列出／刪除的裝置端 API）已實作：`TemplateStore.kt` 新增 `list()`/`delete()`，`WorkbenchServer.kt` 新增 `GET /scripts/{id}/templates`、`DELETE /scripts/{id}/templates/{name}`（跟既有的 PUT 一樣會補 `fileChanges` 廣播，VS Code 本機鏡像才會同步刪除）。`templateSync.ts` 加 `listTemplates`/`deleteTemplate`，`mirrorPanel.ts` 轉發 `requestTemplates`/`deleteTemplate` 兩個新訊息。webview 端把 `savedTemplates`（本地快取）整個換成 `deviceTemplates`（scriptId → 裝置回報的清單，每次存檔／刪除成功都重新拉一次，不自己猜）；「3 · 測試模板」加了「腳本」選單，模板下拉跟「2 · 建立模板」共用同一份裝置清單。已知限制：`templates.json` 沒有存閾值（threshold），所以模板清單只顯示名稱與 ROI 尺寸，閾值純粹是「測試模板」工具列上的即時滑桿，跟選了哪個模板無關。

起因：webview「測試模板」模式目前的比對結果、延遲、Lua snippet 全部是 mock。要接成真的，
device 端 `WorkbenchServer.kt` 只有 `PUT /scripts/{id}/templates/{name}`（存模板），沒有任何
「拿目前畫面跟某個模板比對一次／持續比對」的端點。

## 查證結果（先講這個，決定了下面的方案）

- 真正的比對邏輯已經存在，而且是實測有效的：`vision.find/wait/wait_any`（[docs/lua-api.md](../lua-api.md)），
  底層呼叫 `cv::matchTemplate`（[VisionMatcher.cpp](../../engine/src/main/cpp/VisionMatcher.cpp)、
  [ADR-0003](../adr/0003-opencv-for-vision-matching.md)）。**不需要新寫比對演算法或碰 C++/JNI**。
- 但比對只透過 Lua 腳本的 native 執行路徑觸發：`LuaNative.nativeStart(..., scriptDir: String)`
  固定讀 `<scriptDir>/main.lua`（[LuaNative.kt:29-40](../../engine/src/main/java/com/xaxaxax/moonclicker/lua/LuaNative.kt)），
  沒有「丟一段原始碼進去跑」或「換一個進入檔名」的介面。
- 執行是全域單例，一次只能跑一份（`ScriptSession` 註解：「一次一個是刻意的」），
  `scriptRunner.isRunning()` 已經在 `/scripts/{id}/run` 擋 409（[WorkbenchServer.kt:543](../../app/src/main/java/com/xaxaxax/moonclicker/workbench/WorkbenchServer.kt)）。
  → 「測試模板」跟使用者正在跑的真實腳本天生互斥，這是既有限制的自然延伸，不是新問題。
- log/data 即時串流（`ScriptEngine.logLines` / `sharedData`）已經是全域廣播，`/` WebSocket 已經在推，
  `mirrorPanel.ts` → webview 的 `streamEvent` 也已經在收（[mirror.js](../../vscode-extension/media/mirror.js) 的
  `handleStreamEvent`）。**這條路完全不用動。**
- **目前 webview 的 mock Lua snippet 是錯的**：寫的是 `mc.matchTemplate` / `mc.tap`，真正的 API 是
  `vision.find` / `input.tap`。這個要修，不管走哪個方案。

## 方案比較

| | A. 新開一支獨立 JNI 一次性比對 | **B. 產生暫存 Lua 腳本，走既有執行路徑（推薦）** |
|---|---|---|
| 要動的層 | C++（`VisionMatcher`）＋ JNI 宣告＋ Kotlin | 純 Kotlin（`WorkbenchServer.kt`） |
| 新增比對邏輯 | 是——要在 native 端另開一條不經過 Lua 的呼叫路徑 | 否——原封不動用 `vision.find` |
| 结果回傳 | 要另外設計一個新的回傳通道 | 沿用既有 log/data WebSocket，webview 端零改動 |
| 跟真實腳本的互斥 | 需要自己重新處理（native 引擎本來就是單例，繞過 Lua 不代表繞過這個限制，還可能繞出新的競態） | 天然沿用 `ScriptSession` 現有的單例保護 |
| 風險 | 高——碰 native 又是新路徑，不容易在既有測試覆蓋到 | 低——比對本身是已驗證過的既有路徑，新東西只有「怎麼組出那段 Lua」跟「怎麼發起它」 |

**推薦 B**，理由如上。

## B 方案細節

1. **暫存腳本資料夾**：`<scriptsRoot>/.__vision_test__/`（隱藏資料夾，開頭 `.`，`ScriptStore.scan`
   已經是掃資料夾列表，只要 scan 時排除 `.` 開頭即可，見下方待確認 B1）。每次測試覆寫
   這份資料夾底下的 `main.lua`，不用清乾淨重建。
2. **產生的 `main.lua`**（範例）：
   ```lua
   while true do
     local hit = vision.find({
       image = "<模板圖片絕對路徑>",  -- vision.find 支援絕對路徑，不用複製圖片過去
       roi = { x = <x>, y = <y>, w = <w>, h = <h> },
       threshold = <threshold>,
     })
     if hit then
       data.set("visionTest", { hit = true, confidence = hit.confidence, cx = hit.cx, cy = hit.cy })
     else
       data.set("visionTest", { hit = false })
     end
     sleep(<intervalMs, 預設 300>)
   end
   ```
   圖片路徑用絕對路徑指回真正腳本的資料夾（`docs/lua-api.md`：「相對腳本目錄或絕對路徑」），
   不用複製或連結模板圖片。
3. **新端點**：
   - `POST /scripts/{id}/vision-test`　body `{ image, roi:{x,y,w,h}, threshold, intervalMs? }`
     → 產生上述 `main.lua`、用合成的 `Script(dir = .__vision_test__)` 呼叫既有
     `scriptSession.start(...)`。跟 `/run` 一樣，`isRunning()` 為真時回 409。
   - `POST /run/stop`（新增，通用）→ 呼叫既有但目前沒接 HTTP 的 `ScriptSession.stop()`。
     真實腳本跟 vision-test 共用同一個 `scriptSession`，這一支同時補上「腳本執行中途沒有
     停止端點」這個既有缺口（`/run` 只能等腳本自己結束）。
4. **webview 端**：「測試模板」不再顯示固定 mock 文字，改讀 `data.visionTest`（跟 Console/Data
   分頁用的是同一條 `streamEvent`，`handleStreamEvent` 加一個 case 即可）；Lua snippet 生成器
   改成真正的 `vision.find`/`input.tap` 格式（連同這次順手修掉，不用等這階段）。
5. **ROI 拖曳**：webview 端已經是真的（對著即時畫面算像素座標），只是「調整 ROI」跟「開始測試」
   目前互相獨立——要接線時把「開始測試」按下去才真的呼叫 `POST .../vision-test`，滑桿/ROI
   變動時用 debounce 重新呼叫（換一組參數等於重跑一次，不能邊跑邊改）。

## 已定案

1. 方案 B（暫存 Lua 腳本、沿用既有執行路徑）。
2. `ScriptStore.scan` 排除 `.` 開頭的資料夾，`.__vision_test__` 不會出現在使用者的腳本清單裡——
   實作時一併处理，屬於 B 方案第 1 步的一部分。
3. `intervalMs` 預設 300ms。
4. Lua snippet 的 API 名稱已修正（`mc.matchTemplate`/`mc.tap` → `vision.find`/`input.tap`），
   見 [mirror.js](../../vscode-extension/media/mirror.js) 的 `updateSnippet()`，先於本階段落地、
   已完成。

## 已實作

- [x] `ScriptStore.scan` 排除 `.` 開頭的資料夾（`.__vision_test__` 不會出現在腳本清單）
- [x] `POST /scripts/{id}/vision-test`（[VisionTestScript.kt](../../app/src/main/java/com/xaxaxax/moonclicker/workbench/VisionTestScript.kt) 產生暫存 `main.lua`，透過新增的 `ScriptRunner.startOnDisplay` 指到正確的虛擬顯示，而不是 `script.json` 的預設 target）
- [x] `POST /run/stop`（補 `ScriptSession.stop()` 的 HTTP 入口，真實腳本與 vision-test 共用同一執行槽，冪等）
- [x] `mirrorPanel.ts` 轉發 `startVisionTest`/`stopRun`；`mirror.js` 的 `handleStreamEvent` 讀 `data.visionTest`
- [x] 「測試模板」的「開始測試／停止測試」按鈕；ROI 拖曳／閾值變動時若正在測試，debounce 後 stop→start 重新呼叫（同一執行槽不能並行）；離開測試模式會自動停止，不留孤兒迴圈
