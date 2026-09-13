# ReLC Lua API 參考手冊 (v3)

> 本文件描述 `:engine` 原生 Lua 引擎（`ScriptRuntime.cpp` / `LuaBindings.cpp`）實際綁定的函式。
> 依 [ADR-0005](adr/0005-lua-api-doc-is-conceptual-not-frozen.md)，這份 API 仍會變動，不是凍結的相容性契約。
>
> v2 的 `match.*` / `screen.findImage` / `config.templates` / `display.*` / `on_tick` **全部移除**，
> 舊腳本不會在 v3 上跑。

## 執行模型

一份腳本 = 一個資料夾，裡面必須有 `main.lua`。它就是一段從上到下執行的程式：

```lua
app.launch("com.example.game")
local btn = vision.wait({ image = "login.png" }, 30000)
if btn then
    input.tap(btn.cx, btn.cy)
end
sleep(1000)
log("done")
```

跑到底就結束（狀態 `Finished`）。**沒有 `on_start`、沒有 `on_tick`、沒有 `config` 全域表。**
會阻塞的呼叫（`sleep`、`vision.wait`、`input.*`）就真的在腳本的執行緒上阻塞；
使用者按下停止時，這些呼叫會立刻中斷、腳本被展開（狀態 `Stopped`，不是 `Error`）。

多檔腳本：`package.path` 會指向腳本資料夾，所以 `require("helpers")` 直接可用。

### 座標

**所有對外座標都是邏輯空間**——旋轉 90/270 時長寬互換的那個，也就是 `injectMotionEvent`
用的座標系。影格其實在 surface 空間，但轉換在引擎內部完成，腳本不需要知道它存在
（見 `CONTEXT.md` 的「Surface 空間 / 邏輯空間」）。

**模板圖也一樣**：截圖時看到什麼方向就存什麼方向，顯示器轉了也不必重截。引擎會把模板轉到
影格的方向再比對（[ADR-0013](adr/0013-templates-are-logical-space.md)）。

但**尺寸**不會自動配：`TM_CCOEFF_NORMED` 不是尺度不變的，`scale` 是把影格與模板一起縮小來
省時間，不是拿來配不同大小的目標。目標在不同方向被重新排版成不同像素尺寸時（響應式網頁
就是這樣），還是得分別準備模板。

### 目標顯示器

腳本**不建立也不銷毀顯示器**——目標由 App 在啟動時決定（Scripts 頁的「在指定顯示器執行」，
或 `script.json` 的 `display`）。腳本只會作用在那一個顯示器上，所以 `input.*` 不需要 displayId 參數。

只有虛擬顯示拿得到影格。**跑在實體螢幕上時 `vision.*` 會直接拋出 Lua 錯誤**，
不是靜默找不到——用 `screen.has_vision` 可以先問。

---

## 全域

### `log(...)`
輸出到 Logcat，tag 為 `LuaScript`。接受多個參數，非字串會自動 `tostring`，以 tab 分隔。

### `sleep(ms)`
暫停 `ms` 毫秒。被使用者停止時會中斷腳本。

---

## `screen`

唯讀欄位，每次讀取都是即時值（旋轉會改變 width/height）。

| 欄位 | 說明 |
|---|---|
| `screen.width` / `screen.height` | 目標顯示器的**邏輯**尺寸 |
| `screen.rotation` | `0..3`，對應 `Surface.ROTATION_*` |
| `screen.has_vision` | 這個目標是否有影格來源（虛擬顯示為 `true`） |

---

## `vision`

v2 的 `match.*` 與 `screen.findImage` 合併成這一套。**模板在呼叫時指定，沒有全域模板表**，
而且比對只在呼叫時發生——腳本在 `sleep` 或做輸入時 OpenCV 完全不跑。

### Request

```lua
{
    image = "btn.png",       -- 必填；相對腳本資料夾（也接受絕對路徑）
    threshold = 0.8,         -- 選填，預設 0.8（TM_CCOEFF_NORMED）
    scale = 1.0,             -- 選填，0 < scale <= 1，比對前把影格與模板一起縮小
    gray = false,            -- 選填，轉灰階後比對
    roi = { x, y, w, h },    -- 選填，限制搜尋範圍（邏輯座標；也接受 {x=..,y=..,w=..,h=..}）
}
```

只給圖片路徑時可以簡寫成字串：`vision.find("btn.png")`。

### Result

命中時回傳一個 table，找不到回傳 `nil`：

```lua
{ x, y, w, h,    -- 外框（左上角與尺寸）
  cx, cy,        -- 中心點——要點它就餵這兩個給 input.tap
  confidence }
```

### `vision.find(request)`
對**目前最新的影格**比對一次，不等待。回傳 result 或 `nil`。

### `vision.wait(request, timeout_ms)`
等到出現為止，`timeout_ms` 預設 10000。回傳 result 或逾時的 `nil`。

### `vision.wait_any(requests, timeout_ms)`
同時等多個目標。回傳 `index, result`（index 是 1-based，對應傳入的順序），逾時回傳 `nil`。

```lua
local i, hit = vision.wait_any({
    { image = "ok.png" },
    { image = "error.png" },
}, 15000)
if i == 1 then input.tap(hit.cx, hit.cy) end
```

---

## `input`

全部作用在目標顯示器上，座標是邏輯座標。點擊與滑動會等到動作做完才返回。

| 函式 | 說明 |
|---|---|
| `input.tap(x, y [, hold_ms])` | 單點點擊，`hold_ms` 預設 50 |
| `input.swipe(points [, duration_ms])` | 沿座標序列滑動，`duration_ms` 預設 300 |
| `input.multi_swipe(map [, duration_ms])` | 多指同時滑動，`map` 是 `{[pointerId] = points}` |
| `input.down(id, x, y)` | 按下並保持 |
| `input.move(id, x, y)` | 移動一個按住中的 pointer |
| `input.up(id)` | 放開 |
| `input.key(keycode)` | 注入按鍵（Android KeyEvent keycode） |
| `input.back()` / `input.home()` / `input.recents()` | 常用按鍵的捷徑 |

`points` 可以是攤平的 `{x1, y1, x2, y2, ...}`，也可以是巢狀的 `{{x1,y1}, {x2,y2}, ...}`。

用了 `input.down` 就要記得 `input.up`；忘了的話引擎會在腳本結束時幫你放開，
但中途的行為會像手指一直按著。

---

## `app`

### `app.launch(package_name)`
在目標顯示器上啟動 app。回傳 boolean。

---

## `device`

跳出顯示器邊界的動作。

| 函式 | 說明 |
|---|---|
| `device.notify(title [, text])` | 發一則系統通知（與執行狀態通知分開） |
| `device.open_uri(uri)` | 以 `Intent.parseUri` 解析並開啟 |

---

## `data`

### `data.set(key, value)`
把鍵值發佈給 App，顯示在腳本詳情頁。`value` 可以是 number / string / boolean / table
（table 會以 JSON 字串過橋）。傳 `nil` 會移除該鍵。

---

## 收尾：`on_stop()`

若腳本定義了全域函式 `on_stop`，引擎會在**正常結束、錯誤、使用者停止**三種路徑上各呼叫一次。
適合放釋放按住的觸控之類的收尾。它不是第二套執行模型——沒有迴圈，只會被呼叫一次。

```lua
function on_stop()
    input.up(1)
end
```

---

## 完整範例

```lua
-- 資料夾內容：main.lua、login.png、script.json

if not screen.has_vision then
    log("這份腳本需要跑在虛擬顯示上")
    return
end

log("螢幕尺寸", screen.width, screen.height)
app.launch("com.example.game")

local login = vision.wait({ image = "login.png", threshold = 0.85 }, 30000)
if not login then
    device.notify("自動簽到", "等不到登入按鈕")
    return
end

input.tap(login.cx, login.cy)
sleep(2000)

local i, hit = vision.wait_any({
    { image = "reward.png" },
    { image = "already_claimed.png" },
}, 20000)

if i == 1 then
    input.tap(hit.cx, hit.cy)
    data.set("status", "claimed")
elseif i == 2 then
    data.set("status", "already claimed")
else
    data.set("status", "timeout")
end

input.back()
```

對應的 `script.json`：

```json
{
  "name": "自動簽到",
  "description": "開遊戲、登入、領每日獎勵",
  "display": { "width": 1080, "height": 2400, "densityDpi": 440 }
}
```

`display` 省略時，腳本預設跑在實體螢幕上（那裡沒有 `vision.*`）。
