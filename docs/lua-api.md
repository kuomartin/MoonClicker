# ReLC Lua API 參考手冊

本文件定義 `:engine` 原生 Lua 引擎（Lua 5.5）綁定的全域函式與模組介面。

---

## 執行模型

* **線性執行**：腳本由 `main.lua` 第一行循序執行至末行結束（狀態為 `Finished`）。
* **同步阻塞**：阻塞呼叫（`sleep`、`vision.wait`、`input.*`）於腳本執行緒中等待；使用者停止時立即中斷並展開調用棧（狀態為 `Stopped`）。
* **模組載入**：`package.path` 預設包含腳本目錄，支援 `require("module")` 載入同目錄下其他 `.lua` 檔案。
* **邏輯座標系統**：所有輸入座標（`input.*`）與影像搜尋範圍（`roi`）、命中結果（`cx`, `cy`）皆採用**邏輯空間座標**（旋轉 90°/270° 時長寬自動對調）。
* **模板像素**：模板圖片方向自動對齊邏輯畫面；模板尺寸需與目標在螢幕上的邏輯像素大小一致。
* **目標綁定**：腳本作用於啟動時指定的目標顯示器（虛擬顯示器或本機實體螢幕）。

---

## 全域函式

### `log(...)`
輸出至 Android Logcat（Tag: `LuaScript`）。多參數以 Tab 分隔，非字串自動轉型為字串。

### `sleep(ms)`
暫停目前腳本執行緒 `ms` 毫秒。中途停止時立即中斷。

### `on_stop()` (選擇性回呼)
若腳本宣告全域函式 `on_stop()`，引擎會在執行結束（正常完工、拋錯、使用者手動停止）時觸發一次，用於釋放資源或重置狀態。

```lua
function on_stop()
    input.up(1)
end
```

---

## `screen` 顯示器狀態與鏡像

提供目標顯示器的狀態與鏡像管線控制。

| 欄位 / 函式 | 型別 / 回傳 | 說明 |
|---|---|---|
| `screen.width` | `integer` | 目標顯示器的邏輯寬度。腳本啟動當下的快照，整場執行固定不變 |
| `screen.height` | `integer` | 目標顯示器的邏輯高度。腳本啟動當下的快照，整場執行固定不變 |
| `screen.rotation` | `integer` | 旋轉角度（`0`: 0°, `1`: 90°, `2`: 180°, `3`: 270°）。腳本啟動當下的快照，整場執行固定不變 |
| `screen.has_vision` | `boolean` | 是否具備畫面辨識來源（虛擬顯示一律為 `true`；實體螢幕需啟動鏡像）。即時值 |
| `screen.is_mirror_active` | `boolean` | 鏡像管線是否運作中。即時值 |
| `screen.start_mirror()` | `boolean` | （實體螢幕專用）啟動鏡像管線以啟用 `vision.*` 與 `input.*` |
| `screen.stop_mirror()` | `boolean` | （實體螢幕專用）手動停止鏡像管線 |

---

## `vision` 影像模板比對

基於 OpenCV `TM_CCOEFF_NORMED` 演算法，按需對最新畫面進行比對。

### 請求規格（`VisionRequest`）

```lua
{
    image = "login.png",     -- [必填] 模板圖片路徑（相對腳本目錄或絕對路徑）
    threshold = 0.8,         -- [選填] 相似度門檻（預設 0.8，範圍 0.0 ~ 1.0）
    scale = 1.0,             -- [選填] 降採樣加速因子（預設 1.0，範圍 0.0 < scale <= 1.0）
    gray = false,            -- [選填] 是否轉為灰階比對（預設 false）
    roi = { x, y, w, h },    -- [選填] 搜尋區域邏輯座標（支援 {x,y,w,h} 或 {x=..,y=..,w=..,h=..}）
}
```

*簡寫形式*：可直接傳入圖片檔名字串，如 `vision.find("login.png")`。

### 命中結果（`VisionHit`）

```lua
{
    x = 100, y = 200,        -- 命中區域左上角邏輯座標
    w = 80,  h = 40,         -- 命中區域寬高
    cx = 140, cy = 220,      -- 命中區域中心點邏輯座標（直接用於 input.tap）
    confidence = 0.95        -- 信心度 (0.0 ~ 1.0)
}
```

### 比對函式

* **`vision.find(request)` -> `VisionHit?`**
  對目前最新影格立即比對一次，未命中回傳 `nil`。

* **`vision.wait(request [, timeout_ms])` -> `VisionHit?`**
  持續等待目標出現直到逾時（預設 `10000` ms），逾時回傳 `nil`。

* **`vision.wait_any(requests [, timeout_ms])` -> `(integer?, VisionHit?)`**
  同時等待多個目標（陣列），回傳第一個命中的 `(1-based 索引, VisionHit)`，逾時回傳 `nil, nil`。

---

## `input` 事件注入

注入事件至目標顯示器，座標皆為邏輯像素。

| 函式 | 參數說明 |
|---|---|
| `input.tap(x, y [, hold_ms])` | 單點點擊指定座標，`hold_ms` 按住時長預設 `50` ms |
| `input.swipe(points [, duration_ms])` | 沿路徑滑動，`points` 支援 `{x1,y1,x2,y2}` 或 `{{x1,y1},{x2,y2}}`，預設 `300` ms |
| `input.multi_swipe(map [, duration_ms])` | 多指滑動，`map` 格式為 `{[pointerId] = points}` |
| `input.down(id, x, y)` | 在指定座標按下並保持觸控點 `id`（整數） |
| `input.move(id, x, y)` | 移動觸控點 `id` 至新座標 |
| `input.up(id)` | 釋放觸控點 `id` |
| `input.key(keycode)` | 注入 Android 系統 KeyCode |
| `input.back()` | 注入返回鍵（`AKEYCODE_BACK`） |
| `input.home()` | 注入 Home 鍵（`AKEYCODE_HOME`） |
| `input.recents()` | 注入多工任務鍵（`AKEYCODE_APP_SWITCH`） |

---

## `app` 應用程式管理

* **`app.launch(package_name)` -> `boolean`**
  在目標顯示器啟動指定 Package Name 之 App。成功送出啟動請求回傳 `true`。

---

## `device` 系統互動

* **`device.notify(title [, text])`**
  發送一則獨立的 Android 系統通知。
* **`device.open_uri(uri)`**
  以 Android `Intent.ACTION_VIEW` 開啟指定 URI（如網址或自訂 Schema）。

---

## `data` 執行狀態共享

* **`data.set(key, value)`**
  發布鍵值資料至 ReLC 應用程式端（顯示於腳本詳情頁與 Workbench）。
  * `value` 支援 `number`, `string`, `boolean`, `table`（自動序列化為 JSON）。
  * 傳入 `nil` 可刪除該鍵。

---

## 完整範例

```lua
-- main.lua
log("開始執行自動化腳本，目標尺寸:", screen.width, "x", screen.height)

-- 啟動目標 App
app.launch("com.example.game")

-- 等待登入按鈕出現
local loginBtn = vision.wait({ image = "login_btn.png", threshold = 0.85 }, 20000)
if not loginBtn then
    device.notify("腳本狀態", "登入按鈕超時未出現")
    data.set("status", "login_timeout")
    return
end

-- 點擊登入按鈕中心點
input.tap(loginBtn.cx, loginBtn.cy)
sleep(2000)

-- 同時等待兩種後續狀態
local idx, hit = vision.wait_any({
    { image = "claim_reward.png" },
    { image = "already_claimed.png" }
}, 15000)

if idx == 1 then
    input.tap(hit.cx, hit.cy)
    data.set("status", "reward_claimed")
elseif idx == 2 then
    data.set("status", "already_claimed")
else
    data.set("status", "state_timeout")
end

input.back()
data.set("stage", "completed")
```
