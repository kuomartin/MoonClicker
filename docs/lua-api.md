# ReLC ScriptEngine — Lua API

說明對應程式：`app/src/main/java/com/xaxaxax/relc/script/ScriptEngine.kt`、`app/src/main/java/com/xaxaxax/relc/script/runner/LuaScriptRunner.kt`。

---

## 執行順序（腳本類型為 Lua）

1. **`init`**（可空）— 先執行一次  
2. **`code`** — 依 **`loopMode`** 重複執行（見下）
3. **`clean`**（可空）— 預設僅在腳本**正常跑完**時執行；若在 UI 勾選 **永遠執行 clean**，則中斷／錯誤後仍會執行。`clean` 內發生的例外會被吞掉，不往上拋。

### `loopMode`（`app/src/main/java/com/xaxaxax/relc/script/runner/ScriptMacroLoop.kt`）

- `count == -1`：無限迴圈；每輪跑完 `code` 後 `delay(duration)`，再下一輪。
- 否則：`repeat(count)` 次；輪與輪之間 `delay(duration)`。  
  常見：`LoopMode.None` 等同 `count == 1` 且 `duration` 為 0，主邏輯只跑一輪。

---

## 全域 `displayId`

引擎啟動時會設定 **`displayId = 0`**（Lua 全域變數）。所有 **`input.*`** 都會讀取**當下的** `displayId`，不再在每個函式上傳 display 參數。

範例：

```lua
displayId = 2
input.tap(50, 100, 200)
```

`display.launch(packageName, displayId)` 仍可明確指定要在哪個顯示上啟動 App（第二參數照常傳）。

---

## 專案注入的全域函式與函式庫

### `log(msg)`

- 將字串送到 UI 的 log，並寫入 `Timber.d`。

### `sleep(ms)`

- **阻塞目前執行緒**（`Thread.sleep`），單位為毫秒。腳本在 IO thread 上執行，會阻塞該次 `execute`。

### `input`

`swipe` / `swipeL1`：第一個參數為總歷時（毫秒），之後為連續 `x, y`；**參數總個數為奇數且 ≥ 5**（至少兩個座標點）。

| 呼叫 | 說明 |
|------|------|
| `input.tap(durationMs, x, y)` | 在 `ACTION_DOWN` 與 `ACTION_UP` 之間持續 `durationMs`（毫秒）；使用全域 `displayId`。 |
| `input.swipe(durationMs, x1, y1, x2, y2, ...)` | 折線滑動（**L2** 弧長：每段 `√(Δx²+Δy²)`）。 |
| `input.swipeL1(durationMs, x1, y1, x2, y2, ...)` | 同上，但以 **L1（曼哈頓）弧長** 分配時間：每段貢獻 `|Δx|+|Δy|`；指尖軌跡仍在頂點間直線插值。 |
| `input.down(pointerId, x, y)` | 觸控按下（多指流程）；使用全域 `displayId`。 |
| `input.move(pointerId, x, y)` | 觸控移動。 |
| `input.up(pointerId)` | 觸控放開（僅 pointer id；display 依全域 `displayId`）。 |

### `display`

| 呼叫 | 說明 |
|------|------|
| `display.launch(packageName, displayId)` | 在指定顯示上啟動應用程式；回傳值由底層服務決定。 |

程式註解標有 TODO：`display.create`、`display.destroy` **尚未提供**。

---

## Luaj 預設環境

以 `JsePlatform.standardGlobals()` 建立，包含一組常見的 Lua 5.2 風格標準庫與 JVM 相關擴充（如 `string`、`math`、`table` 等）。細節以 [Luaj](https://github.com/luaj/luaj) 行為為準；本專案未另外自訂 `require` 路徑或沙箱。

---

## 目前未在 Lua 暴露的能力

- **實體按鍵注入**（BACK、HOME 等）：`ScriptEngine` 未綁定至 Lua；若要在腳本裡送按鍵，可使用 **SIMPLE** 腳本類型的 `key` 步驟（見 Kotlin 端 `InputController.injectPhysicalKey`）。

---

## 範例

```lua
displayId = 0
log("start")
input.tap(50, 540, 960)
sleep(500)
input.swipe(300, 100, 800, 900, 800, 300, 500)
input.swipeL1(300, 100, 800, 900, 800, 300, 500)
log("done")
```
