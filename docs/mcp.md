# MCP server

App 的腳本工作台內建 [MCP](https://modelcontextprotocol.io) server，讓 AI agent 直接操作與觀察裝置：點擊、滑動、截圖、讀 view tree、在 REPL 執行 Lua、啟動與停止腳本。它和 VS Code 共用同一個 port 與 Workbench Auth Token，不經過 VS Code。

## 設定

1. 依[擴充套件說明](https://github.com/kuomartin/moonclicker-vscode/blob/master/README.zh-TW.md)開啟腳本工作台，並用 VS Code 連上裝置一次（配對取得 token）。
2. 在 VS Code 執行 **MoonClicker: 複製 MCP Server 設定**，剪貼簿會是這樣的 `mcpServers` 設定：

   ```json
   {
     "mcpServers": {
       "moonclicker": {
         "type": "http",
         "url": "http://192.168.1.5:8787/mcp",
         "headers": { "Authorization": "Bearer <token>" }
       }
     }
   }
   ```

3. 貼到 agent 的 MCP 設定，例如 Claude Code 專案根目錄的 `.mcp.json`、Claude Desktop 或 Cursor 的設定檔。

設定裡有 token，拿到它就能控制裝置，不要提交到版本控制。token 失效（例如清除 App 資料）時重新配對再複製一次。

傳輸是 Streamable HTTP，無狀態：`POST /mcp` 每次回一則 JSON，不開 SSE 串流。

## 工具

座標一律是顯示器邏輯空間的像素，與 Lua 的 `input.tap`、`ui` 節點的 `cx`/`cy` 相同；超出顯示器範圍時回錯誤。顯示器 id 從 `list_displays` 取得，0 是實體螢幕。

| 工具 | 說明 |
| --- | --- |
| `list_displays` | 顯示器 id、名稱、邏輯尺寸、鏡像是否開啟。 |
| `screenshot` | 一張 PNG。長邊超過 1280 時等比縮小，文字說明換算比例。實體螢幕要先開鏡像。 |
| `view_tree` | 視窗與節點樹（Android 11 以上），一行一個節點：類別、文字、描述、id、`@cx,cy`、狀態。看不到的節點省略，沒有內容也不能操作的節點只留子節點。`raw: true` 回完整 JSON，格式同 [lua-api.md](lua-api.md) 的 `ui.dump()`。 |
| `tap` | 點擊一點，`durationMs` 預設 50；600 以上是長按。 |
| `swipe` | 兩點之間直線滑動，`durationMs` 預設 300。 |
| `repl_eval` | 在 REPL 執行 Lua，API 與腳本相同。`log()` 輸出與回傳值分成兩段文字（`log:`、`result:`）；錯誤以 `isError` 回報。超過 `timeoutMs`（預設 30 秒）就中斷。 |
| `repl_reset` | 清空 REPL 的全域變數。 |
| `list_scripts` | 裝置上的腳本與是否執行中。 |
| `start_script` / `stop_script` | 啟動腳本；停止一支，或省略 `scriptId` 時全部停止。 |
| `script_logs` | 腳本最近的 log（每支最多 500 行，從腳本工作台啟動後開始收）。 |

## REPL

MCP 在每個用到的顯示器上各開一個自己的 REPL，第一次 `repl_eval` 時開啟，之後沿用，全域變數保留到 `repl_reset`。它和 VS Code 的 REPL 一樣算在裝置上最多四個的上限內；VS Code 執行 **開啟 REPL** 時可以接上它，看到 agent 跑的每一段程式碼。這個 REPL 被關掉時，下一次 `repl_eval` 會重開一個新的。
