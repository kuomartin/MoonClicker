---@meta

-- MoonClicker Lua API 的 LuaLS 型別提示 stub。對照 docs/lua-api.md 與
-- engine/src/main/cpp/LuaBindings.cpp/.h 的實際 API 表面撰寫。
-- 這份檔案只給編輯器提供語法提示與補全，不會被裝置執行，也不會同步進 Script Folder。

--- 輸出到 Logcat（tag 為 `LuaScript`）。多個參數以 tab 分隔，非字串會自動 `tostring`。
---@param ... any
function log(...) end

--- 暫停 `ms` 毫秒；使用者中途停止腳本時，這個呼叫會立刻中斷。
---@param ms integer
function sleep(ms) end

--- 若定義，正常結束、錯誤、使用者停止三種路徑都會各呼叫一次，用來做收尾
--- （例如放開 `input.down` 還按著的觸控）。不是第二套執行模型，只會被呼叫一次。
function on_stop() end

---@class moonclicker.Screen
---@field width integer 目標顯示器的邏輯寬度。腳本啟動當下的快照，整場執行固定不變
---@field height integer 目標顯示器的邏輯高度。腳本啟動當下的快照，整場執行固定不變
---@field rotation integer `0..3`，對應 `Surface.ROTATION_*`。腳本啟動當下的快照，整場執行固定不變
---@field has_vision boolean 這個目標是否有影格來源（虛擬顯示或已開啟鏡像之實體螢幕為 `true`；實體螢幕未開鏡像呼叫 `vision.*` 會拋錯）
---@field is_mirror_active boolean 鏡像管線是否作用中（虛擬顯示一律為 `true`；實體螢幕依開啟狀態而定）

--- 唯讀欄位與鏡像管線控制方法。`width`/`height`/`rotation` 是啟動當下的快照，不會隨顯示器
--- 中途旋轉更新；`has_vision`/`is_mirror_active` 才是每次讀取都是即時值。
---@type moonclicker.Screen
screen = {}

--- (實體螢幕專用) 開啟實體螢幕鏡像管線並附加影格接收器。成功回傳 true，失敗拋出錯誤。已開啟時重複呼叫會安全返回 true。
---@return boolean
function screen.start_mirror() end

--- (實體螢幕專用) 釋放由本腳本開啟的實體螢幕鏡像管線引用。成功回傳 true。
---@return boolean
function screen.stop_mirror() end

---@class moonclicker.VisionRoiRect
---@field x integer 外框左上角 x
---@field y integer 外框左上角 y
---@field w integer 外框寬
---@field h integer 外框高

--- 限制搜尋範圍（邏輯座標）。接受具名欄位 `{ x = .., y = .., w = .., h = .. }` 或陣列 `{ x, y, w, h }`。
---@alias moonclicker.VisionRoi moonclicker.VisionRoiRect | [integer, integer, integer, integer] | integer[]

--- `image` 與 `text` 擇一。
---@class moonclicker.VisionRequest
---@field image string? 相對腳本資料夾的圖片路徑（也接受絕對路徑）
---@field text string? 要找的文字；需要先在設定頁下載 OCR 元件
---@field threshold number? 選填，預設 0.8；模板是 `TM_CCOEFF_NORMED` 分數，文字是 Levenshtein 相似度
---@field scale number? 選填，只用於 image，`0 < scale <= 1`，比對前把影格與模板一起縮小；不是拿來配不同大小的目標
---@field gray boolean? 選填，只用於 image，轉灰階後比對，預設 false
---@field exact boolean? 選填，只用於 text，與整行比對而不是找子字串，預設 false；`123` 預設會命中 `1234`
---@field roi moonclicker.VisionRoi? 選填，限制搜尋範圍（邏輯座標）；文字請求強烈建議給，整張辨識很慢

---@class moonclicker.VisionHit
---@field x number 外框左上角 x
---@field y number 外框左上角 y
---@field w number 外框寬
---@field h number 外框高
---@field cx number 中心點 x，餵給 `input.tap`
---@field cy number 中心點 y，餵給 `input.tap`
---@field confidence number 與要找的東西有多像（0～1）：模板是比對分數，文字是相似度；`threshold` 比較的就是它
---@field text string? 只有文字請求才有：命中的那一整行

--- `vision.read`／`read_lines` 的一行辨識結果。
---@class moonclicker.TextLine
---@field text string 辨識出的文字
---@field confidence number OCR 對這段文字的信心度（0～1），不是相似度
---@field x number 外框左上角 x
---@field y number 外框左上角 y
---@field w number 外框寬
---@field h number 外框高
---@field cx number 中心點 x
---@field cy number 中心點 y

---@class moonclicker.Vision
vision = {}

--- 對目前最新的影格比對一次，不等待。找不到回傳 `nil`。
---@param request moonclicker.VisionRequest|string 完整的 request 物件或圖片路徑字串簡寫
---@return moonclicker.VisionHit? hit 命中結果，找不到為 `nil`
---@overload fun(image: string): moonclicker.VisionHit?
function vision.find(request) end

--- 對目前最新的影格比對多個目標一次，不等待；回傳依列表順序第一個命中的 1-based index 與結果，都找不到回傳 `nil`。
---@param requests (moonclicker.VisionRequest|string)[] 目標列表（可混用 request table 或圖片路徑字串）
---@return integer? index 1-based 索引，找不到為 `nil`
---@return moonclicker.VisionHit? hit 命中結果，找不到為 `nil`
function vision.find_any(requests) end

--- 等到出現為止，逾時回傳 `nil`。
---@param request moonclicker.VisionRequest|string 完整的 request 物件或圖片路徑字串簡寫
---@param timeout_ms integer? 逾時毫秒數，選填，預設 10000
---@param step_ms integer? 兩次比對開始的最小間隔（毫秒），選填，預設 0（每張新影格都比對）
---@return moonclicker.VisionHit? hit 命中結果，逾時為 `nil`
---@overload fun(image: string, timeout_ms?: integer, step_ms?: integer): moonclicker.VisionHit?
function vision.wait(request, timeout_ms, step_ms) end

--- 同時等多個目標；命中哪個就回傳它的 1-based index 與結果，逾時回傳 `nil`。
---@param requests (moonclicker.VisionRequest|string)[] 目標列表（可混用 request table 或圖片路徑字串）
---@param timeout_ms integer? 逾時毫秒數，選填，預設 10000
---@param step_ms integer? 兩次比對開始的最小間隔（毫秒），選填，預設 0（每張新影格都比對）
---@return integer? index 1-based 索引，逾時為 `nil`
---@return moonclicker.VisionHit? hit 命中結果，逾時為 `nil`
function vision.wait_any(requests, timeout_ms, step_ms) end

--- 把 `roi` 當成單一文字行直接辨識（不經偵測），讀數值用；辨識不出任何字回傳 `nil`。只看最新影格，不等待。
---@param roi moonclicker.VisionRoi 要讀的區域（邏輯座標），框緊一點，避免把邊框讀成字
---@return moonclicker.TextLine? line
function vision.read(roi) end

--- 偵測 `roi`（省略為整張）內的每一行後逐行辨識，依由上而下、由左而右排序；沒有字回傳空陣列。比 `read` 慢。
---@param roi moonclicker.VisionRoi? 要讀的區域（邏輯座標）
---@return moonclicker.TextLine[] lines
function vision.read_lines(roi) end

---@alias moonclicker.InputPoints (number[])|(number[][]) 攤平的 `{x1,y1,x2,y2,...}` 或巢狀的 `{{x1,y1},{x2,y2},...}`

---@class moonclicker.Input
input = {}

--- 單點點擊。
---@param x number 邏輯座標 x
---@param y number 邏輯座標 y
---@param hold_ms integer? 按住毫秒數，選填，預設 50
function input.tap(x, y, hold_ms) end

--- 沿座標序列滑動。
---@param points moonclicker.InputPoints 座標點序列
---@param duration_ms integer? 滑動毫秒數，選填，預設 300
function input.swipe(points, duration_ms) end

--- 多指同時滑動。
---@param map table<integer, moonclicker.InputPoints> 多指座標對應 `{[pointerId] = points}`
---@param duration_ms integer? 滑動毫秒數，選填，預設 300
function input.multi_swipe(map, duration_ms) end

--- 按下並保持——記得之後要呼叫 `input.up`，忘了的話引擎會在腳本結束時幫你放開，
--- 但中途的行為會像手指一直按著。
---@param id integer 觸控點 ID（pointerId）
---@param x number 邏輯座標 x
---@param y number 邏輯座標 y
function input.down(id, x, y) end

--- 移動一個按住中的 pointer。
---@param id integer 觸控點 ID（pointerId）
---@param x number 邏輯座標 x
---@param y number 邏輯座標 y
function input.move(id, x, y) end

--- 放開指定的觸控點。
---@param id integer 觸控點 ID（pointerId）
function input.up(id) end

--- 注入按鍵（Android `KeyEvent` keycode）。
---@param keycode integer Android KeyEvent keycode（例如 4 為 BACK、3 為 HOME）
---@return boolean ok 是否成功注入
function input.key(keycode) end

--- 模擬按下返回鍵（`AKEYCODE_BACK`）。
---@return boolean ok 是否成功注入
function input.back() end

--- 模擬按下 Home 鍵（`AKEYCODE_HOME`）。
---@return boolean ok 是否成功注入
function input.home() end

--- 模擬按下多工/最近任務鍵（`AKEYCODE_APP_SWITCH`）。
---@return boolean ok 是否成功注入
function input.recents() end

---@class moonclicker.App
app = {}

--- 在目標顯示器上啟動 app。
---@param package_name string 應用程式套件名稱（Package Name）
---@return boolean ok 是否成功發送啟動請求
function app.launch(package_name) end

---@class moonclicker.AppTask
-- `package` 也是 LuaLS 的可見性修飾字，不先寫 `public` 會被吃掉，欄位名變成 `string`。
---@field public package string task 根 activity 的套件名稱
---@field display_id integer task 所在的顯示器

--- 列出所有顯示器上有 task 的 app，與目標顯示器無關。同一 app 在同一顯示器只列一筆；不過濾 launcher 等系統元件；不保證順序。
--- 查詢失敗時拋錯；空表代表確實沒有 app 在執行。
---@return moonclicker.AppTask[] tasks
function app.list() end

--- 讓 app 不出聲、不搶音訊焦點；`muted = false` 時還原。與目標顯示器無關，整個 app 都會靜音（主螢幕也是）。
--- 狀態由 UserService 持有：腳本結束仍維持，直到解除或 UserService 停止。已取得的音訊焦點不會被收回，要在 `app.launch` 之前呼叫。
---@param package_name string 應用程式套件名稱（Package Name）
---@param muted boolean 是否靜音（必填）
---@return boolean ok app 未安裝或系統拒絕設定時為 false
function app.mute(package_name, muted) end

---@class moonclicker.Device
device = {}

--- 發一則系統通知（與執行狀態通知分開）。
---@param title string 通知標題
---@param text string? 通知內文，選填，預設空字串
function device.notify(title, text) end

--- 以 `Intent.parseUri` 解析並開啟。
---@param uri string 目標 URI（例如 `https://...` 或 `intent:...`）
function device.open_uri(uri) end

---@class moonclicker.Data
data = {}

--- 把鍵值發佈給 App，顯示在腳本詳情頁。`value` 傳 `nil` 會移除該鍵；
--- table 會以 JSON 字串過橋。
---@param key string 鍵名
---@param value number|string|boolean|table|nil 發佈的值（傳 `nil` 刪除）
function data.set(key, value) end
