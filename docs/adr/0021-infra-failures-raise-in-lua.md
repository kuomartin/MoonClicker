---
status: accepted
---

# Lua API 的基礎設施失敗拋 Lua error，操作失敗回 false

與服務溝通的 Lua 函式把失敗分成兩類。基礎設施失敗（UserService 斷線或內部異常、裝置不支援）一律拋 Lua error，訊息為 `"<module.func>: <原因>"`，原因取例外的 message，沒有 message 時用例外的類別名稱。操作本身沒成功（按鍵注入被拒、找不到要啟動的 App、系統拒絕設定靜音）回傳 `false`。`ScriptHost` 不 catch，讓基礎設施失敗的例外穿到 native，由單一 upcall helper 轉成 Lua error。

理由：腳本能分支處理的只有操作失敗，例如換一個 package 名稱再試，或改用別的路徑。基礎設施失敗腳本無從補救；若也回 `false`，腳本通常只會在 `sleep` 迴圈裡空轉，使用者看到的是腳本一直在跑卻什麼都沒發生，而不是一則說明原因的錯誤。

## Considered Options

- **一律拋錯**：腳本不必檢查回傳值，但「package 名稱打錯」這類腳本本來就該處理的情況，也得全部包進 `pcall`。
- **一律回 `false`**：最寬鬆，但服務斷線、腳本寫錯與操作被拒三者無法區分，錯誤原因只留在 logcat。

## Consequences

- 每個新函式都要在設計時決定每種失敗屬於哪一類，並寫進 `docs/lua-api.md`。
- 服務端元件若在 AIDL 之前就把內部失敗吞成 `false`，Lua 端也只會看到 `false`；這類失敗要在服務端改成拋例外，Lua 端不需改動。
- 不是 Lua API 的 upcall（`ScriptHost.attachSink` 在腳本啟動時、`close()` 在腳本結束後、`onEngineEvent`）執行時沒有 Lua 堆疊可以拋錯，照舊吞掉失敗並記 log。
