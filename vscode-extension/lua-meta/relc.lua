---@meta

-- ReLC Lua API v3 的 LuaLS 型別提示 stub（見 #59）。手工對照 docs/lua-api.md（v3）與
-- engine/src/main/cpp/LuaBindings.cpp/.h 的實際 API 表面撰寫，不是機械生成——
-- docs/lua-api.md 不是凍結契約（見 ADR-0005），LuaBindings.cpp 的 binding 也沒有結構化
-- 到能自動抽出型別。這份檔案只給編輯器用，不會被裝置執行，也不會同步進 Script Folder。

--- 輸出到 Logcat（tag 為 `LuaScript`）。多個參數以 tab 分隔，非字串會自動 `tostring`。
---@param ... any
function log(...) end

--- 暫停 `ms` 毫秒；使用者中途停止腳本時，這個呼叫會立刻中斷。
---@param ms integer
function sleep(ms) end

--- 若定義，正常結束、錯誤、使用者停止三種路徑都會各呼叫一次，用來做收尾
--- （例如放開 `input.down` 還按著的觸控）。不是第二套執行模型，只會被呼叫一次。
function on_stop() end

---@class relc.Screen
---@field width integer 目標顯示器的邏輯寬度，旋轉時會跟著變
---@field height integer 目標顯示器的邏輯高度，旋轉時會跟著變
---@field rotation integer `0..3`，對應 `Surface.ROTATION_*`
---@field has_vision boolean 這個目標是否有影格來源（虛擬顯示為 `true`；實體螢幕上呼叫 `vision.*` 會直接拋錯）

--- 唯讀欄位，每次讀取都是即時值。
---@type relc.Screen
screen = {}

---@class relc.VisionRoi
---@field [1] integer
---@field [2] integer
---@field [3] integer
---@field [4] integer
---@field x integer
---@field y integer
---@field w integer
---@field h integer

---@class relc.VisionRequest
---@field image string 必填，相對腳本資料夾的圖片路徑（也接受絕對路徑）
---@field threshold number? 預設 0.8（`TM_CCOEFF_NORMED`）
---@field scale number? `0 < scale <= 1`，比對前把影格與模板一起縮小；不是拿來配不同大小的目標
---@field gray boolean? 轉灰階後比對
---@field roi relc.VisionRoi? 限制搜尋範圍（邏輯座標）

---@class relc.VisionHit
---@field x number 外框左上角 x
---@field y number 外框左上角 y
---@field w number 外框寬
---@field h number 外框高
---@field cx number 中心點 x，餵給 `input.tap`
---@field cy number 中心點 y，餵給 `input.tap`
---@field confidence number

---@class relc.Vision
vision = {}

--- 對目前最新的影格比對一次，不等待。找不到回傳 `nil`。
---@param request relc.VisionRequest
---@return relc.VisionHit? hit
---@overload fun(image: string): relc.VisionHit?
function vision.find(request) end

--- 等到出現為止，逾時回傳 `nil`。
---@param request relc.VisionRequest
---@param timeout_ms integer? 預設 10000
---@return relc.VisionHit? hit
---@overload fun(image: string, timeout_ms?: integer): relc.VisionHit?
function vision.wait(request, timeout_ms) end

--- 同時等多個目標；命中哪個就回傳它的 1-based index 與結果，逾時回傳 `nil`。
---@param requests (relc.VisionRequest|string)[]
---@param timeout_ms integer? 預設 10000
---@return integer? index
---@return relc.VisionHit? hit
function vision.wait_any(requests, timeout_ms) end

---@alias relc.InputPoints (number[])|(number[][]) 攤平的 `{x1,y1,x2,y2,...}` 或巢狀的 `{{x1,y1},{x2,y2},...}`

---@class relc.Input
input = {}

--- 單點點擊。
---@param x number
---@param y number
---@param hold_ms integer? 預設 50
function input.tap(x, y, hold_ms) end

--- 沿座標序列滑動。
---@param points relc.InputPoints
---@param duration_ms integer? 預設 300
function input.swipe(points, duration_ms) end

--- 多指同時滑動。
---@param map table<integer, relc.InputPoints> `{[pointerId] = points}`
---@param duration_ms integer? 預設 300
function input.multi_swipe(map, duration_ms) end

--- 按下並保持——記得之後要呼叫 `input.up`，忘了的話引擎會在腳本結束時幫你放開，
--- 但中途的行為會像手指一直按著。
---@param id integer
---@param x number
---@param y number
function input.down(id, x, y) end

--- 移動一個按住中的 pointer。
---@param id integer
---@param x number
---@param y number
function input.move(id, x, y) end

--- 放開。
---@param id integer
function input.up(id) end

--- 注入按鍵（Android `KeyEvent` keycode）。
---@param keycode integer
---@return boolean ok
function input.key(keycode) end

---@return boolean ok
function input.back() end

---@return boolean ok
function input.home() end

---@return boolean ok
function input.recents() end

---@class relc.App
app = {}

--- 在目標顯示器上啟動 app。
---@param package_name string
---@return boolean ok
function app.launch(package_name) end

---@class relc.Device
device = {}

--- 發一則系統通知（與執行狀態通知分開）。
---@param title string
---@param text string?
function device.notify(title, text) end

--- 以 `Intent.parseUri` 解析並開啟。
---@param uri string
function device.open_uri(uri) end

---@class relc.Data
data = {}

--- 把鍵值發佈給 App，顯示在腳本詳情頁。`value` 傳 `nil` 會移除該鍵；
--- table 會以 JSON 字串過橋。
---@param key string
---@param value number|string|boolean|table|nil
function data.set(key, value) end
