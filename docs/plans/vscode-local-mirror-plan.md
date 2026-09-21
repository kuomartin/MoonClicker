# VS Code 擴充套件改回本機鏡像＋自動同步

取代 [vscode-fsprovider-plan.md](vscode-fsprovider-plan.md) 的 C/D（VS Code 擴充套件架構）。
起因：LuaLS 是原生行程直接 `io.open` 磁碟路徑，讀不到 `moonclicker:` virtual scheme，
無法在 virtual 資料夾內提供型別提示（該計畫 E2 已查證）。A/B（裝置端單檔案 API、
`file_change` WebSocket 推播）完全保留、原封不動沿用——只是用途從「直接服務虛擬檔案
系統」變成「驅動背景同步」。

體感差異：virtual FS 開資料夾是即時的；本機鏡像模式開資料夾要先整份 pull 完才能用，
跟舊手動 pull 一樣要等（腳本大小、模板圖片數量決定要等多久），只是不用再手動按一次。

## A. 新增/改動的檔案

| 檔案 | 內容 |
|---|---|
| `src/scriptMirror.ts`（新） | 本機鏡像核心邏輯，走既有 `scriptSync.ts` 的細粒度 API：`mirrorRoot()` 算出 `context.globalStorageUri` 底下的隱藏路徑；`pullScriptToMirror()` 整份下載（`getTree` + 逐檔 `readFile`）寫入，同雜湊的檔案跳過下載，不比對 mtime、不做衝突偵測（比照 `writeFile` 的 overwrite 語意）；`pushFileToDevice`/`deleteFileOnDevice`/`renameFileOnDevice` 給 watcher 呼叫；收到 `file_change` 時先比對雜湊再決定要不要重新下載（見 Q2） |
| `WorkbenchServer.kt` `/scripts/:id/tree` | `ScriptTreeEntry` 加 `sha256: String`（目錄該欄位空字串），逐檔案 `MessageDigest` 計算（見 Q2） |
| `src/extension.ts` | 不再 `registerFileSystemProvider`；`openScriptCommand` 改成先 `pullScriptToMirror` 再把本機資料夾掛成 workspace folder；加 `onDidSaveTextDocument`（存檔推上去）與每個鏡像資料夾各自的 `FileSystemWatcher`（建立/刪除/rename 推上去）；`connection` 的 `fileChange` 事件改成重新下載該檔案覆蓋本機 |
| `src/moonclickerFileSystemProvider.ts` | 保留但不註冊——哪天 LuaLS 支援 virtual workspace 就能重新掛回來，不用重寫 |
| `src/treeViews.ts` | `openScriptAsWorkspaceFolder` 改成非同步、跑 pull 進度提示（`vscode.window.withProgress`） |

## B. 已定案的問題

1. **鏡像資料夾位置**：`context.globalStorageUri` 底下隱藏路徑，使用者不用管。
2. **衝突處理**：比照 `writeFile` 覆蓋語意——單向覆蓋，不比對 mtime、不跳警告。本機推裝置、裝置推本機都一樣。
3. **`moonclickerFileSystemProvider.ts`**：留著不刪，不註冊。
4. **self-echo**：本機推上去的改動，裝置會透過同一條 `file_change` WebSocket 廣播回來——要濾掉，不然會重新下載自己剛寫的東西。inotify/WebSocket 推播沒有帶「誰觸發的」資訊，只能用「最近由本地端主動推送的 path，時間窗內忽略」這種近似判斷（不是精確解，但跟現有 self-write 廣播本來就沒有做請求來源標記一致）。

## C. 已定案

- **Q1** 同步時機：本機→裝置只在**存檔**（`onDidSaveTextDocument`）時推，不是每個 keystroke；建立/刪除另外掛 `FileSystemWatcher`，改名掛 `onDidRenameFiles`。裝置→本機收到 `file_change` 就重新下載那個檔案、覆蓋本機、reload 開著的 buffer。✅ 確認。
- **Q2** 加逐檔案雜湊（不是完整 Merkle tree）：`/scripts/:id/tree` 的 `ScriptTreeEntry` 多一個 `sha256` 欄位。用途：(a) self-echo 判斷不再靠「時間窗」這種近似值，直接比對收到的雜湊跟本機檔案雜湊是否相同；(b) 整份 pull 時本機已有同雜湊檔案就跳過下載。腳本資料夾檔案數量小，不需要 Merkle 樹狀聚合。✅ 確認。
