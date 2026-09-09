# ReLC Lua API 參考手冊 (v2)

> 本文件描述的是**原生 Lua 引擎**（`RelcEngine.cpp`，透過 `NativeLuaScriptRunner` 執行 `.lua` 腳本）實際綁定的函式。另外還有一套獨立的、JSON 為基礎的「SIMPLE」腳本格式（`SimpleScriptRunner.kt`），其 Kotlin `InputController` 提供 `swipePolyline`/`swipePolylineL1` 等函式，但那**不是** Lua API，不在本文件範圍內。

目前所有的 API 分為五大模組：**日誌 (Log)**、**輸入 (Input)**、**顯示 (Display)**、**圖像匹配 (Match / Screen)** 以及 **設定 (Config)**。

## 1. 全域函數 (Global)
### `log(message)`
在 Android Logcat 中輸出偵錯訊息。標籤為 `LuaScript`。
*   **參數**: `message` (string) - 要輸出的文字。
*   **範例**: `log("腳本啟動中...")`

---

## 2. 輸入模組 (input)
### `input.click(x, y)`
在目前顯示器上執行單點點擊（內部以 50ms 的按下-放開模擬）。

### `input.swipe(pointerId, points, durationMs, keep)`
以指定 `pointerId` 沿座標點序列滑動。`points` 可以是扁平的 `{x1, y1, x2, y2, ...}`，也可以是巢狀的 `{{x1,y1}, {x2,y2}, ...}`。`keep` (boolean) 為 `true` 時，滑動結束後保持按住（不放開）。

> 備註：目前引擎綁定沒有 `displayId` 參數、也沒有 `input.tap`、`input.swipePolyline(L1)`、`input.script.*`——這些名稱只存在於 SIMPLE 腳本格式（見上方提示），Lua 引擎不提供。

---

## 3. 顯示模組 (display)
### `display.create(width, height, densityDpi = 440, flags = 16)`
建立一個新的虛擬顯示器 (Virtual Display)。畫面會自動串接到 C++ 辨識引擎。
*   **參數**:
    * `width`, `height` (number): 解析度。
    * `densityDpi` (number, 選填): 螢幕密度，預設 440。
    * `flags` (number, 選填): 系統旗標，預設 16 (PUBLIC)。
*   **傳回值**: `displayId` (number) 或 `-1` (失敗)。

> 備註：`display.create` 的底層對應為原生虛擬顯示器建立，內部會建立 Surface 作為渲染來源，Surface 的熱替換交由 DisplaySink 控制。

### `display.launchInDisplay(packageName, displayId)`
在指定的顯示器中啟動應用程式。
*   **參數**:
    * `packageName` (string): 應用程式包名 (e.g., "com.android.settings").
    * `displayId` (number, 選填): 目標顯示器 ID，預設為最近建立的 ID。
*   **傳回值**: `boolean` (是否啟動成功)。

### `display.get_all()`
取得目前系統中所有的虛擬顯示器 ID。
*   **傳回值**: `table` (數字列表，例如 `{0, 1, 10}`)。

---

## 4. 圖像匹配模組 (match / screen)
此模組透過 C++ 與 OpenCV 進行底層加速，每幀執行 `cv::matchTemplate`（TM_CCOEFF_NORMED）。

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

### `match.enable(name)` / `match.disable(name)`
啟用／停用單一模板的比對（不需要重新指派整個 `match.templates`）。

### `match.set_enabled(name, enabled)`
`match.enable`/`match.disable` 的底層函式，直接指定 boolean 狀態。

### `screen.findImage(name)`
同步查詢單一模板目前是否已被辨識到（不掛起腳本），是 `match.wait()` 的輕量替代方案。
*   **傳回值**: `{ found = true, x, y, confidence }` 或 `{ found = false }`。

---

## 5. 設定模組 (config)
`config` 是 `match.templates` 之外，另一種指定模板與調校比對迴圈的方式；若同時存在，`config.templates` 優先於 `match.templates`。
*   **`config.templates`**: 與 `match.templates` 相同結構。
*   **`config.fps`** (number): 比對迴圈的執行頻率（預設約 15 FPS）。
*   **`config.scale`** (number): 比對前畫面縮放比例，用於降低運算量。

---

## 6. 腳本生命週期回呼 (Callbacks)
你可以定義以下函數，引擎會在特定時間點呼叫它們：

*   **`on_start()`**: 腳本載入後第一次執行前呼叫。
*   **`on_tick(matches, tick_num)`**: 每秒約呼叫 15 次（15 FPS 邏輯循環）。
    *   `matches`: 一個包含目前畫面所有辨識到目標的 table。
    *   `tick_num`: 自腳本啟動以來的累計跳動次數。

---

## 範例腳本：自動啟動、監控與處理

這個範例展示了如何利用 `on_start` 初始化，以及在 `on_tick` 中同時處理圖像辨識與計時邏輯。

```lua
match.templates = {
    { name = "登入按鈕", target = "/data/local/tmp/login.png", threshold = 0.8 }
}

local last_seen_tick = 0

function on_start()
    log("開始執行自動登入...")
    local dId = display.create(1080, 1920)
    display.launchInDisplay("com.example.game", dId)
end

function on_tick(matches, tick)
    -- 如果看到目標，更新最後看到的時間
    if matches["登入按鈕"] and matches["登入按鈕"].found then
        last_seen_tick = tick
        log("看到登入按鈕了，點擊它！")
        input.click(matches["登入按鈕"].x, matches["登入按鈕"].y)
    end

    -- 如果超過 150 ticks (約 10 秒) 沒看到任何目標，輸出警告
    if tick - last_seen_tick > 150 then
        log("警告：已超過 10 秒未辨識到任何目標...")
        last_seen_tick = tick -- 重置計時以避免日誌洗版
    end
end
```
