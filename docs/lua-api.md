# ReLC Lua API 參考手冊 (v2)

目前所有的 API 分為四大模組：**日誌 (Log)**、**輸入 (Input)**、**顯示 (Display)** 以及 **圖像匹配 (Match)**。

## 1. 全域函數 (Global)
### `log(message)`
在 Android Logcat 中輸出偵錯訊息。標籤為 `LuaScript`。
*   **參數**: `message` (string) - 要輸出的文字。
*   **範例**: `log("腳本啟動中...")`

---

## 2. 輸入模組 (input)
### `input.swipe(pointerId, points, duration, keep)`
執行單點或多點滑動（或點擊）。
*   **參數**:
    *   `pointerId` (number): 手指 ID (通常從 0 開始，-1 代表由系統分配)。
    *   `points` (table): 座標點清單。格式為 `{x1, y1, x2, y2, ...}` 或雙層 table `{{x1, y1}, {x2, y2}}`。
    *   `duration` (number, 選填): 滑動持續時間（毫秒），預設為 0。
    *   `keep` (boolean, 選填): 結束後是否保持手指按下狀態，預設為 `false`。
*   **範例**: 
    ```lua
    -- 在 (100, 100) 點擊一下
    input.swipe(0, {100, 100}, 50)
    -- 從 (100, 100) 滑動到 (500, 500)，耗時 500ms
    input.swipe(0, {100, 100, 500, 500}, 500)
    ```

---

## 3. 顯示模組 (display)
### `display.create(width, height, densityDpi, flags)`
建立一個新的虛擬顯示器 (Virtual Display)。畫面會自動串接到 C++ 辨識引擎。
*   **參數**:
    *   `width`, `height` (number): 解析度。
    *   `densityDpi` (number, 選填): 螢幕密度，預設 440。
    *   `flags` (number, 選填): 系統旗標，預設 16 (PUBLIC)。
*   **傳回值**: `displayId` (number) 或 `nil` (失敗)。

### `display.launch(packageName, displayId)`
在指定的顯示器中啟動應用程式。
*   **參數**:
    *   `packageName` (string): 應用程式包名 (e.g., "com.android.settings")。
    *   `displayId` (number, 選填): 目標顯示器 ID，預設為最近建立的 ID。
*   **傳回值**: `boolean` (是否成功啟動)。

### `display.get_all()`
取得目前系統中所有的虛擬顯示器 ID。
*   **傳回值**: `table` (數字列表，例如 `{0, 1, 10}`)。

---

## 4. 圖像匹配模組 (match)
此模組透過 C++ 與 OpenCV 進行底層加速。

### `match.templates` (Table 屬性)
定義要搜尋的目標模板。
*   **結構**:
    ```lua
    match.templates = {
        { name = "目標A", target = "/sdcard/a.png", threshold = 0.8 },
        { name = "目標B", target = "/sdcard/b.png", threshold = 0.9 }
    }
    ```

### `match.wait()`
掛起腳本直到 `match.templates` 中的任何一個目標被辨識到。
*   **傳回值**: 一個包含辨識結果的 table。
*   **資料格式**:
    ```lua
    local results = match.wait()
    if results["目標A"] and results["目標A"].found then
        log("找到目標A，位置在: " .. results["目標A"].x .. ", " .. results["目標A"].y)
    end
    ```

---

## 5. 腳本生命週期回呼 (Callbacks)
你可以定義以下函數，引擎會在特定時間點呼叫它們：

*   **`on_start()`**: 腳本載入後第一次執行前呼叫。
*   **`on_tick(dt)`**: 每秒約呼叫 15 次（15 FPS 邏輯循環），`dt` 為間隔時間。
*   **`on_match(name, results)`**: 當畫面辨識到任何模板時主動呼叫。
    *   `name`: 匹配到的第一個模板名稱。
    *   `results`: 完整的匹配結果 table。

---

## 範例腳本：自動啟動、監控與超時處理

這個範例展示了如何利用 `on_start` 初始化、`on_match` 處理圖像辨識，以及 `on_tick` 處理超時邏輯。

```lua
match.templates = {
    { name = "登入按鈕", target = "/data/local/tmp/login.png", threshold = 0.8 }
}

local last_seen_time = 0

function on_start()
    log("開始執行自動登入...")
    local dId = display.create(1080, 1920)
    display.launch("com.example.game", dId)
end

function on_tick(dt)
    last_seen_time = last_seen_time + dt
    
    -- 如果超過 10 秒沒看到任何目標，每 10 秒輸出一次警告
    if last_seen_time > 10 then
        log("警告：已超過 10 秒未辨識到任何目標...")
        last_seen_time = 0 -- 重置計時以避免日誌洗版
    end
end

function on_match(name, results)
    -- 只要有匹配，就重置計時器
    last_seen_time = 0

    if name == "登入按鈕" then
        local btn = results[name]
        log("看到登入按鈕了，點擊它！")
        input.swipe(-1, {btn.x, btn.y}, 100)
    end
end
```
