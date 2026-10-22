# MoonClicker Lua API 參考手冊

本文件定義 `:engine` 原生 Lua 引擎（Lua 5.5）綁定的全域函式與模組介面。

---

## 執行模型

* **線性執行**：腳本由 `main.lua` 第一行循序執行至末行結束（狀態為 `Finished`）。
* **同步阻塞**：阻塞呼叫（`sleep`、`vision.wait`、`ui.wait`、`input.*`）於腳本執行緒中等待；使用者停止時立即中斷並展開調用棧（狀態為 `Stopped`）。
* **模組載入**：`package.path` 預設包含腳本目錄，支援 `require("module")` 載入同目錄下其他 `.lua` 檔案。
* **邏輯座標系統**：所有輸入座標（`input.*`）與影像搜尋範圍（`roi`）、命中結果與元件外框（`cx`, `cy`）皆採用**邏輯空間座標**（旋轉 90°/270° 時長寬自動對調）。
* **模板像素**：模板圖片方向自動對齊邏輯畫面；模板尺寸需與目標在螢幕上的邏輯像素大小一致。
* **目標綁定**：腳本作用於啟動時指定的目標顯示器（虛擬顯示器或本機實體螢幕）。
* **同時執行**：不同顯示器上的腳本可以同時執行，各自有獨立的 Lua state、`log` 與 `data`，彼此不能溝通。一個顯示器同時只跑一支腳本（實體螢幕也算一個），同一支腳本同時也只跑一份。
* **錯誤與失敗**：與服務溝通的函式分兩種失敗。**基礎設施失敗**（UserService 斷線或異常、裝置不支援）拋 Lua error，訊息為 `"<module.func>: <原因>"`，例如 `app.mute: android.os.DeadObjectException`；**操作本身沒成功**（例如按鍵注入被拒、找不到要啟動的 App）回傳 `false`。前者腳本無從補救，不處理就結束並顯示錯誤；需要時可以用 `pcall` 接住。參數錯誤也拋 Lua error。

---

## `script.json` 腳本設定

腳本資料夾裡的 `script.json`，只有 `uniqueId` 是必要欄位。

```json
{
  "uniqueId": "my-script",
  "name": "我的腳本",
  "description": "顯示在 App 腳本清單上的說明",
  "display": { "width": 720, "height": 1280, "densityDpi": 320, "keyboard": true }
}
```

| 欄位 | 說明 |
|---|---|
| `uniqueId` | 腳本的識別，小寫英數字、`.`、`_`、`-`。缺少時腳本無法執行 |
| `name`、`description` | 顯示在 App 與 VS Code 上；`name` 省略時用資料夾名稱 |
| `display` | 省略時腳本預設跑在本機螢幕；給了就在執行時建立（或沿用同名的）虛擬顯示器 |
| `display.width`、`display.height` | 虛擬顯示器的尺寸（px） |
| `display.densityDpi` | 預設 `320` |
| `display.keyboard` | 預設 `true`。設成 `false` 時，這個顯示器上的 app 不綁定輸入法：`input.text` 送出的按鍵不會被使用者的輸入法（例如注音）組成中文字，代價是在全螢幕畫面點輸入框也不會出現鍵盤。用到 `input.text` 的腳本應設成 `false`；只在腳本建立顯示器時生效，在既有顯示器上已經開著的 app 要重新啟動才會套用；需要 Android 12 以上 |

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
| `screen.start_mirror()` | `boolean` | （實體螢幕專用）啟動鏡像管線以啟用 `vision.*` 與 `input.*`。成功或已開啟回傳 `true`；取不到鏡像時拋錯 |
| `screen.stop_mirror()` | `boolean` | （實體螢幕專用）歸還本腳本 `start_mirror()` 取得的鏡像；沒有取得過時回傳 `false`。腳本結束時會自動歸還 |

---

## `vision` 影像模板比對與文字辨識

按需對最新畫面比對：模板以 OpenCV `TM_CCOEFF_NORMED` 比對，文字以 PP-OCRv6 辨識（ADR-0018）。文字辨識需要先在設定頁「文字辨識（OCR）」下載 OCR 元件，未安裝時呼叫會報錯。

第一次呼叫 `vision.*`（腳本開始後，或 `screen.start_mirror()` 之後）時，若還沒收到畫面，引擎會先等到第一張（最多 2 秒），所以腳本第一行就可以呼叫 `vision.*`；不用辨識的腳本不受影響。

### 請求規格（`VisionRequest`）

`image` 與 `text` 擇一。

```lua
{
    image = "login.png",     -- 模板圖片路徑（相對腳本目錄或絕對路徑）
    text = "開始",           -- 要找的文字
    threshold = 0.8,         -- [選填] 門檻（預設 0.8，範圍 0.0 ~ 1.0）：模板是比對分數，文字是相似度
    roi = { x, y, w, h },    -- [選填] 搜尋區域邏輯座標（支援 {x,y,w,h} 或 {x=..,y=..,w=..,h=..}）
    scale = 1.0,             -- [選填，只用於 image] 降採樣加速因子（預設 1.0，範圍 0.0 < scale <= 1.0）
    gray = false,            -- [選填，只用於 image] 是否轉為灰階比對（預設 false）
    exact = false,           -- [選填，只用於 text] 與整行比對，而不是在行內找子字串（預設 false）
}
```

*簡寫形式*：可直接傳入圖片檔名字串，如 `vision.find("login.png")`。

### 模板比對的耗時

耗時由搜尋範圍的大小與是否為彩色決定，與模板大小幾乎無關。低階機上全畫面彩色比對一次要 1 秒以上，寫腳本時：

* **盡量給 `roi`**：效果最大。搜尋範圍縮小多少，耗時就大約少多少。
* **模板不靠顏色區分時用 `gray = true`**：彩色是灰階的 3–4 倍。
* **`scale = 0.5`** 讓搜尋面積變成 1/4，效果與 `gray` 相當，兩者可以一起用；縮小後細節變少，小模板可能比不到。

單次比對耗時（ms）：

| 裝置 | 顯示器 | 全畫面、彩色 | 全畫面、`gray = true` | `scale = 0.5` | `roi` 為畫面的 1/8 |
|---|---|---|---|---|---|
| Galaxy A21s（低階） | 720×1280 | 900–1150 | 240–360 | 220–260 | 86–144 |
| Galaxy A21s（低階） | 1080×2400 | 2700–4200 | 850–920 | 740–790 | 350–400 |
| Pixel 7a | 720×1280 | 125–170 | 34–48 | 29–34 | 14–18 |
| Pixel 7a | 1080×2400 | 330–470 | 90–124 | 90–110 | 47–57 |

量測方法與完整數據見 `docs/research/latency.md`。文字辨識的耗時見下一節。

### 文字比對

`text` 請求先偵測 `roi`（省略為整張）內的每一行文字再逐行辨識，取與目標最相近的一行。兩邊都先去除空白、全形英數轉半形，再以字元計算編輯距離（Levenshtein）：

* 預設在行內找最接近目標的一段，相似度 = 1 − 編輯距離 / 目標長度。OCR 常錯一兩個字、字尾常黏著雜訊（`SKIP` 讀成 `SKIPH`），這樣都比得到。
* `exact = true` 時與整行比對，相似度 = 1 − 編輯距離 / 兩者較長者。

相似度達到 `threshold` 才算命中。注意：

* **短字串容錯小**：`OK` 錯一個字相似度就只剩 0.5。
* **數字會被包含**：`123` 會命中 `1234`（相似度 1.0，`threshold = 1` 也擋不住）。等特定數值時用 `exact = true` 並把 `roi` 框到只剩那一行，或改用 `vision.read` 讀出來自己比。
* **整張辨識很慢**：低階機約 2 秒一次。務必給 `roi`，必要時搭配 `step_ms`。

### 命中結果（`VisionHit`）

```lua
{
    x = 100, y = 200,        -- 命中區域左上角邏輯座標
    w = 80,  h = 40,         -- 命中區域寬高
    cx = 140, cy = 220,      -- 命中區域中心點邏輯座標（直接用於 input.tap）
    confidence = 0.95,       -- 與要找的東西有多像 (0.0 ~ 1.0)，threshold 比較的就是它
    text = "確定  取消",      -- 只有文字請求才有：命中的那一整行
}
```

文字命中的框是對上的那段文字，不是整行：同一行有好幾個按鈕字時，`cx`/`cy` 落在要找的那一個上。位置由辨識模型的輸出換算，約準到正負一個字寬；直書的行退回整行的框。

### 比對函式

* **`vision.find(request)` -> `VisionHit?`**
  對目前最新影格立即比對一次，未命中回傳 `nil`。

* **`vision.find_any(requests)` -> `(integer?, VisionHit?)`**
  對目前最新影格比對多個目標（陣列）一次，回傳依陣列順序第一個命中的 `(1-based 索引, VisionHit)`，都未命中回傳 `nil, nil`。

* **`vision.find_all(request)` -> `VisionHit[]`**
  對目前最新影格找出所有命中，依 `confidence` 由高到低，第一個就是 `vision.find` 的結果；沒有命中回傳空陣列。最多 100 個。
  * 模板：彼此重疊超過模板一半的只留最像的那個，同一個目標不會因為偏移一兩個像素而重複出現。
  * 文字：每一行最多一個，取該行最像的那一段；同一行裡出現兩次只會回傳一次。

* **`vision.wait(request [, timeout_ms [, step_ms]])` -> `VisionHit?`**
  持續等待目標出現直到逾時（預設 `10000` ms），逾時回傳 `nil`。

* **`vision.wait_any(requests [, timeout_ms [, step_ms]])` -> `(integer?, VisionHit?)`**
  同時等待多個目標（陣列），回傳第一個命中的 `(1-based 索引, VisionHit)`，逾時回傳 `nil, nil`。

* **`vision.wait_until(fn [, timeout_ms [, step_ms]])` -> `any`**
  每張新影格呼叫一次 `fn()`，直到它回傳 `nil`、`false` 以外的值，並把那個值回傳；逾時（預設 `10000` ms）回傳 `nil`。用於請求表達不了的條件：數值大小、位置、多個目標的組合。
  * `fn` 是一般的 Lua 函式，讀寫得到外層的變數；`fn` 拋的錯照常往外拋。
  * `fn` 執行期間影格固定：裡面呼叫幾次 `vision.find`、`find_all`、`read`、`read_lines` 都看同一張畫面，同一個 `roi` 的文字辨識只做一次。
  * `fn` 裡不能呼叫 `vision.wait`、`wait_any`、`wait_until`（影格固定時等不到新影格），呼叫會報錯。`fn` 應該只做判斷，不要 `sleep` 或 `input.*`。

```lua
-- 等 HP 讀數到 100 以上，回傳讀到的數值
local hp = vision.wait_until(function()
    local line = vision.read({ 20, 40, 200, 30 })
    local n = line and tonumber(line.text:match("%d+"))
    return n and n >= 100 and n
end, 30000)

-- 等畫面下半部出現「領取」
local hit = vision.wait_until(function()
    for _, h in ipairs(vision.find_all({ text = "領取" })) do
        if h.cy > screen.height / 2 then return h end
    end
end)
```

### 讀取文字

* **`vision.read(roi)` -> `TextLine?`**
  把 `roi` 當成單一文字行直接辨識（不經偵測），用於讀取數值。辨識不出任何字回傳 `nil`。七段式數字、`1/2` 這類讀數，直接辨識比偵測後再辨識準確；`roi` 框緊，避免把邊框讀成字。

* **`vision.read_lines([roi])` -> `TextLine[]`**
  偵測 `roi`（省略為整張）內的每一行後逐行辨識，依由上而下、由左而右排序；沒有字回傳空陣列。辨識模型一次只能讀一行，多行區域必須先偵測，所以比 `read` 慢。

兩者都只看最新的一張影格，不等待；要等畫面出現某段文字用 `vision.wait({ text = ... })`。

```lua
{
    text = "12345",
    confidence = 0.98,       -- OCR 對這段文字有多確定 (0.0 ~ 1.0)
    x = 100, y = 200, w = 120, h = 40,
    cx = 160, cy = 220,
}
```

`TextLine.confidence` 與 `VisionHit.confidence` 意思不同：讀取沒有「要找的東西」，只有 OCR 自己的信心度。

### 輪詢間隔

`step_ms` 是兩次比對（`wait_until` 是兩次呼叫 `fn`）開始的最小間隔（預設 `0`：每張新影格都比對一次）。目標不需要即時反應、或比對成本高（大模板、多目標、文字）時調高它可以降低 CPU 用量，代價是命中最多晚 `step_ms` 才回傳。逾時前一定會再比對最後一次。

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
| `input.key(keycode)` | 注入 Android 系統 KeyCode，回傳是否成功注入 |
| `input.text(text)` | 對目標顯示器上目前有焦點的輸入框輸入文字，回傳是否成功注入；呼叫前要先點選輸入框。只支援可印 ASCII、`\n`（Enter）與 `\t`（Tab），含其他字元（例如中文）時拋錯，一個字都不輸入；注入途中失敗時前面的字已經送出。`\n` 在單行搜尋框是送出，在多行輸入框是換行，依 app 而定。送出的是按鍵，使用者的輸入法若會組字（例如注音），打出來會變成中文：在 `script.json` 設 `display.keyboard = false` |
| `input.back()` | 注入返回鍵（`AKEYCODE_BACK`），回傳是否成功注入 |
| `input.home()` | 注入 Home 鍵（`AKEYCODE_HOME`），回傳是否成功注入 |
| `input.recents()` | 注入多工任務鍵（`AKEYCODE_APP_SWITCH`），回傳是否成功注入 |

---

## `app` 應用程式管理

* **`app.launch(package_name)` -> `boolean`**
  在目標顯示器啟動指定 Package Name 之 App。成功送出啟動請求回傳 `true`；App 未安裝或沒有可啟動的畫面時回傳 `false`。
  * 目標是虛擬顯示器、但裝置不支援在次要顯示器上執行 App（沒有宣告 `android.software.activities_on_secondary_displays`，部分 Android 8.x/9 裝置）時拋錯。這是系統開機時決定的，無法以設定或 shell 權限開啟。
* **`app.list()` -> `table`**
  列出所有顯示器上正在執行的 App，與目標顯示器無關。回傳陣列，每筆為 `{ package = "com.example.game", display_id = 2 }`。
  * 「正在執行」指該 App 在某個顯示器上有 task；只有背景 service、沒有畫面的 process 不列入。
  * `package` 取 task 的根 activity：App 在自己的 task 裡開啟其他 App 的畫面（分享、瀏覽器）時，仍算原本的 App。
  * 同一個 App 在同一顯示器上有多個 task 只列一筆；在多個顯示器上則各列一筆。
  * 不過濾系統元件：主螢幕會列出 launcher，也可能列出 SystemUI 等。
  * 不保證順序。
  * 查詢失敗（UserService 異常）時拋錯；回傳空表代表確實沒有 App 在執行。

```lua
for _, task in ipairs(app.list()) do
    log(task.package, task.display_id)
end
```

* **`app.mute(package_name, muted)` -> `boolean`**
  讓指定 App 不出聲，也不搶走其他 App 的音訊焦點（不會打斷主螢幕正在播放的媒體）；`muted` 為 `false` 時還原。與目標顯示器無關。`muted` 必須是 boolean。App 未安裝或系統拒絕設定時回傳 `false`。
  * 靜音狀態由 UserService 持有，不屬於腳本：腳本結束後仍維持，直到呼叫 `app.mute(package_name, false)` 或 UserService 停止。UserService 停止時還原所有靜音；異常結束時，下次啟動時還原。
  * 沒有虛擬顯示器、也沒有腳本或畫面在使用時，UserService 會在 App 退到背景約 30 秒後自動停止，靜音也隨之還原。
  * 實作是對該 App 設定 appops（`PLAY_AUDIO`、`TAKE_AUDIO_FOCUS`），是整個 App 的系統設定、不分顯示器：同一個 App 在主螢幕開啟時也是靜音。
  * 只擋得住之後的音訊焦點請求，App 已經取得的焦點不會被收回，所以要在 `app.launch` 之前呼叫。
  * 靜音期間解除安裝 MoonClicker 不會還原，需自行在系統設定或以 `adb shell appops reset <package>` 處理。

```lua
app.mute("com.example.game", true)
app.launch("com.example.game")
```

---

## `ui` 畫面元件樹

讀目標顯示器上的視窗與元件（文字、resource id、外框），不用模板或 OCR 就能找到按鈕。需要 Android 11（API 30）以上，較舊的版本呼叫時拋錯。

* 只看得到以 Android view（或有提供 semantics 的 Compose）建成的畫面；遊戲等自己繪製的畫面只有一個空的根節點，仍要用 `vision`。
* `RecyclerView` 等清單只有畫面上的項目，畫面外的要先捲動。
* 取樹時才連線，閒置約 30 秒後釋放。系統同時只允許一個 UiAutomation：`adb shell uiautomator` 或 instrumentation 測試正在用時呼叫會拋錯。連線不會停用使用者開著的無障礙服務。
* 第一次取樹約數百毫秒，之後畫面沒變時只要幾毫秒；需要時取一次，不要在迴圈裡不停輪詢。

### 節點（`UiNode`）

```lua
{
    class = "android.widget.Button",
    text = "確定",                  -- 沒有就是 nil，下同
    id = "com.example:id/ok",       -- resource id
    desc = "確定",                  -- contentDescription
    package = "com.example",        -- 所在視窗的套件名稱
    x = 100, y = 200, w = 80, h = 40,
    cx = 140, cy = 220,             -- 中心點邏輯座標（直接用於 input.tap）
    clickable = true, long_clickable = false, checkable = false, checked = false,
    enabled = true, focusable = true, focused = false, selected = false,
    scrollable = false, editable = false,
    visible = true,                 -- 畫面外或被裁掉的為 false
    children = { ... },             -- 子節點陣列
}
```

### 選擇器（`UiSelector`）

所有寫出的欄位都要符合。字串 `"確定"` 是 `{ text = "確定" }` 的簡寫。

| 欄位 | 比對方式 |
|---|---|
| `text`、`desc` | 完全相同 |
| `text_contains`、`desc_contains` | 包含該字串（不是 pattern） |
| `id` | 完全相同，或只寫 `:id/` 之後的部分（`"ok"` 符合 `com.example:id/ok`） |
| `class` | 完全相同，或只寫最後一段（`"Button"` 符合 `android.widget.Button`） |
| `package` | 視窗的套件名稱 |
| `clickable`、`long_clickable`、`checkable`、`checked`、`enabled`、`focusable`、`focused`、`selected`、`scrollable`、`editable`、`visible` | 布林值相同 |

沒寫 `visible` 時只找 `visible = true` 的節點，免得點到畫面外的座標。欄位名稱打錯或型別不對時拋錯。

### 函式

* **`ui.dump()` -> `UiWindow[]`**
  目標顯示器上的視窗，最上層的在前。每個視窗為 `{ type, title, package, root }`：`type` 是 `"application"`、`"input_method"`、`"system"` 等，`root` 是根節點，視窗還沒有內容時為 `nil`。

* **`ui.find(selector)` -> `UiNode?`**
  取一次樹，回傳第一個符合的節點：視窗由上而下，視窗內先父後子、依子節點順序。找不到回傳 `nil`。

* **`ui.find_all(selector)` -> `UiNode[]`**
  取一次樹，依同樣順序回傳所有符合的節點；沒有時為空陣列。

* **`ui.wait(selector [, timeout_ms [, step_ms]])` -> `UiNode?`**
  每 `step_ms`（預設 `200`）取一次樹，直到找到或逾時（預設 `10000` ms），逾時回傳 `nil`。逾時前一定會再找最後一次。

```lua
app.launch("com.example.app")
local ok = ui.wait({ id = "ok", enabled = true })
if ok then input.tap(ok.cx, ok.cy) end
```

---

## `device` 系統互動

* **`device.notify(title [, text])`**
  發送一則獨立的 Android 系統通知。
* **`device.open_uri(uri)`**
  以 Android `Intent.ACTION_VIEW` 開啟指定 URI（如網址或自訂 Schema）。

---

## `data` 執行狀態共享

* **`data.set(key, value)`**
  發布鍵值資料至 MoonClicker 應用程式端（顯示於腳本詳情頁與 Workbench）。
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
