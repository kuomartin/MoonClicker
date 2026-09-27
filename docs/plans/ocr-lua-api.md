# OCR Lua API 計畫（issue #119）

目標：腳本能等待與讀取畫面上的文字。`VisionRequest` 新增 `text` 欄位，`vision.find`／`find_any`／`wait`／`wait_any` 都能等文字；新增讀取 API 回傳辨識出的文字、位置與信心度。OCR 推論與套件安裝已在 issue #118 完成，本計畫只接到腳本執行路徑。

## A. 啟動參數

| # | 位置 | 改動 | 理由 |
|---|---|---|---|
| A1 | `ScriptRun` | 新增 `ocr: OcrRuntime?`（`packDir: File`、`threads: Int`）；`null` 表示未安裝或不支援 | `:engine` 不讀 `:app` 的設定，沿用 `ScriptRun` 傳參 |
| A2 | `ScriptSession` | 注入 `OcrManager`，以 `installedDir` 與 `effectiveThreads` 組出 `OcrRuntime` | 設定頁與執行路徑看同一個狀態 |
| A3 | `LuaNative.nativeStart` → `ScriptRuntime::start` | 多兩個參數：套件目錄（空字串＝沒有）、執行緒數 | — |
| A4 | `ScriptRuntime` | 持有 `std::unique_ptr<Ocr>`，第一次 OCR 呼叫時才建立，腳本結束時釋放；建立失敗轉成 Lua error | 不用 OCR 的腳本不付載入成本（A21s 約 0.6 秒）；ORT 無執行緒限制，直接在腳本執行緒推論 |
| A5 | 未安裝時的錯誤 | `OCR is not installed: download it in Settings → Text recognition (OCR)` | 不支援的 ABI 也走這句，設定頁會顯示不支援 |

## B. `VisionRequest.text`

| # | 項目 | 內容 | 理由 |
|---|---|---|---|
| B1 | 欄位 | `text`（字串）與 `image` 擇一；`exact`（布林，預設 false）只對 `text` 有效，兩者都給或都沒給就報錯；`text` 請求給 `scale`／`gray` 也報錯 | 參數只對模板有意義，靜默忽略會讓人以為有作用 |
| B2 | 比對 | 兩邊先去除空白、全形英數轉半形，以字元（不是位元組）計算 Levenshtein 距離 d。預設找行內最接近目標的子字串（近似子字串比對），相似度 = 1 − d／目標長度；`exact = true` 時改與整行比對，相似度 = 1 − d／max(目標長度, 行長度)。數字不另做處理：`123` 會命中 `1234`，要避免就用 `exact` 並框緊 `roi` | OCR 常錯一兩個字（`禮`→`程`）、字尾黏雜訊（`SKIP`→`SKIPH`），全等比對會漏；雜訊也可能是數字（邊框讀成 `1`），替數字預設邊界規則反而會漏。NDK 的 ICU 要 API 31，minSdk 27 無法做完整的 NFKC |
| B3 | 範圍 | 有 `roi` 就只在 ROI 內偵測＋辨識，沒有就整張 | 低階機整張約 2.3 秒，文件建議一律給 `roi` |
| B4 | 門檻 | `threshold` 對 `text` 是 B2 的相似度下限，預設 0.8；命中結果的 `confidence` 就是這個相似度 | 與模板比對的預設值與語意一致。短字串容錯小（`OK` 錯一字只剩 0.5），文件要寫明 |
| B5 | 命中位置 | CTC 解碼時記下每個字元所在的時間步，換算成行內的水平範圍（扣掉補邊與縮放）；B2 比對出的起訖字元組成子字串的框，`cx`／`cy` 是它的中心。直書的行（辨識前被旋轉過）退回整行的框。結果多一個 `text` 欄位，是整行的辨識結果 | 同一行有多個按鈕字（`確定  取消`）時，點整行中心會點錯；CTC 的字元位置約準到正負一個字寬，點中心夠用 |
| B6 | 同一輪共用 | 一輪比對內，`roi` 相同的 `text` 請求只跑一次偵測＋辨識 | `wait_any` 等多個文字時不重複推論 |
| B7 | 實作位置 | `VisionMatcher::match` 依請求種類分派；OCR 實例由 `ScriptRuntime` 以回呼提供，`matchUntil`／`find`／`find_any` 不變 | 等待、逾時、`step_ms`、`reportHits` 全部共用 |
| B8 | 回報 UI | `reportHits` 的 `name` 對文字請求填 `text:<文字>` | `EngineState.lastVisionResult` 不必改型別 |

## C. 讀取 API

| # | 函式 | 行為 |
|---|---|---|
| C1 | `vision.read(roi)` | ROI 當成單一文字行直接辨識（不經偵測），回傳 `TextLine`：`{text, confidence, x, y, w, h, cx, cy}`；辨識不出任何字回傳 `nil` |
| C2 | `vision.read_lines([roi])` | ROI（省略為整張）內偵測所有行後逐行辨識，回傳依 y、x 排序的陣列，每一項同 C1；沒有字回傳空陣列 |

每個回傳物件只有一個 `confidence`，意思由物件決定：`VisionHit`（`find`／`wait` 的命中，模板或文字）是「與要找的東西有多像」，`threshold` 比較的就是它；讀取 API 回傳的 `TextLine` 是 OCR 對這段文字的信心度（CTC 解碼時每個字元機率的平均），因為讀取沒有要找的東西，相似度不存在。文件與 LuaLS stub 把兩種型別分開寫。文字命中不另外附 OCR 信心度：同一個物件出現兩個分數正是要避免的混亂，需要時再加不破壞相容性。`read_lines` 必須先偵測：辨識模型只吃單一橫向文字行（高 48 px），多行直接辨識會被壓成一行而讀成亂碼。

兩者都只看當下最新的一張影格，不等待；要等畫面出現某段文字用 `vision.wait{text=…}`。

## D. 測試

| # | 位置 | 內容 |
|---|---|---|
| D1 | Tier 0（`RecordingMoonClickerService`） | `text` 與 `image` 同時給、`text` 配 `scale`、未安裝 OCR 時呼叫，都報出明確錯誤 |
| D2 | Tier 1 puppet | `PuppetActivity` 多一個可排程顯示的文字區（例如數字 `12345` 與按鈕字 `START`） |
| D3 | `OcrVisionTest`（Tier 1） | `vision.find{text="START"}` 的位置落在 puppet 實際畫的位置；`vision.read(roi)` 讀出 `12345`；`wait{text=…}` 等到延後出現的文字；不存在的文字回傳 `nil`。沿用 `OcrAccuracyTest` 的做法從 `/data/local/tmp/ocr/` 安裝套件，沒有套件時 `assume` 跳過；影格全黑時比照既有 vision 測試跳過 |
| D4 | JVM／Tier 0 | 近似子字串比對與 `exact`：`SKIPH` 命中 `SKIP`、`合作紀念程包唱` 命中 `合作紀念禮包`、`123` 命中 `1234` 但 `exact` 不命中、`OK` 錯一字不過 0.8；比對邏輯寫成不依賴 ORT 的純函式（`TextMatch.{h,cpp}`），經 `OcrNative.nativeMatchText` 在 androidTest 驗證（比照 `nativeReadImage` 的做法；不需要套件與圖形堆疊，ATD 映像檔就能跑） |

## E. 文件

| # | 檔案 | 改動 |
|---|---|---|
| E1 | `docs/lua-api.md` | `vision` 一節：`text`／`exact` 欄位、比對規則與相似度門檻（含短字串與數字的注意事項）、命中結果的 `text`、`read`／`read_lines`；效能建議（給 `roi`、搭配 `step_ms`） |
| E2 | `vscode-extension/lua-meta/moonclicker.lua` | `VisionRequest.text`／`exact`、`VisionHit.text`、`TextLine`、`vision.read`、`vision.read_lines` |
| E3 | `docs/lua-api-testing.md` | 新增 `OcrVisionTest` 列與套件推送方式 |
