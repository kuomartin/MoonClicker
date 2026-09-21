# VS Code 擴充套件改用 vscode.FileSystemProvider

目標：`moonclicker:` virtual scheme 直接讀寫裝置上的 Script Folder，取代手動 pull/push。裝置端與擴充套件端一起改。

## A. 裝置端新增 API（`WorkbenchServer.kt`）

| Method | Path | 用途 | 取代/沿用 |
|---|---|---|---|
| GET | `/scripts/:id/tree` | 回傳整個 Script Folder 的遞迴 manifest（path、size、mtime、isDirectory），一次拿完給 provider 做 cache | 新增 |
| GET | `/scripts/:id/files/*path` | 讀單一檔案 raw bytes | 新增 |
| PUT | `/scripts/:id/files/*path` | 寫入單一檔案（不存在則建立，含中間目錄） | 新增 |
| DELETE | `/scripts/:id/files/*path` | 刪除單一檔案或目錄（遞迴） | 新增 |
| POST | `/scripts/:id/mkdir/*path` | 建立空目錄（FileSystemProvider 需要獨立的 createDirectory） | 新增 |
| POST | `/scripts/:id/rename` | body `{from, to}`，改檔名/搬移 | 新增 |
| ~~GET `/scripts/:id/export`~~、~~PUT `/scripts/:id/import`~~ | — | 整包 zip | **移除**（HTTP route 拿掉；`ScriptArchive.kt` 本身留著，App 內建的分享/匯入匯出還在用，見 E1） |

新檔案存取需要的路徑防護（zip slip 同款防禦）沿用 `ScriptArchive.kt` 裡 canonicalPath 檢查的邏輯，抽成共用函式，讓新的單檔 API 與既有 zip import 共用，不要各寫一份。

## B. StreamEvent proto 異動（`proto/workbench_stream_event.proto`）

| 項目 | 內容 |
|---|---|
| 新增 oneof 分支 | `FileChangeEvent file_change = 3` |
| FileChangeEvent 欄位 | `scriptId string`、`path string`、`kind enum{CREATED, CHANGED, DELETED}` |
| 觸發時機 | 裝置端任何管道寫入 Script Folder 後廣播：新 HTTP 單檔 API、App 內建輯器（若有）、**以及 `FileObserver`/`WatchService` 監控到的外部改動**（檔案管理員、USB 接電腦直接改）|
| 用途 | 擴充套件收到後 fire `onDidChangeFile`，並局部更新 tree cache，不必整包重抓 `/tree` |
| 裝置端新增項目 | 每個開啟中的 Script Folder（有 provider client 連線期間）掛一個 `FileObserver`，變更 debounce 後推播；避免自己 API 寫入又被自己的 observer 重複推播一次（用短時間內的 self-write 標記去重） |

## C. VS Code 擴充套件架構異動

| 檔案 | 異動 |
|---|---|
| `src/scriptSync.ts` | `pullScript`/`pushScript` 刪除；改成薄薄一層 HTTP client（`getTree`/`readFile`/`writeFile`/`deleteEntry`/`mkdir`/`rename`），仍不依賴 vscode API，維持可單元測試 |
| `src/moonclickerFileSystemProvider.ts`（新） | 實作 `vscode.FileSystemProvider`：`stat`/`readDirectory`/`readFile`/`writeFile`/`delete`/`rename`/`createDirectory`，內部維護每個已開啟 scriptId 的 tree cache；`onDidChangeFile` emitter 接 `WorkbenchConnection` 的 `file_change` event |
| `src/workbenchConnection.ts` | `StreamEvent` union 加 `file_change` case；新增 `onDidReceiveFileChange` listener |
| `src/treeViews.ts` | `LocalScriptItem`/`LocalFileItem`（本機 pull/push 用的節點）整個移除；`RemoteScriptItem` 點擊改成呼叫 `vscode.workspace.updateWorkspaceFolders` 掛上 `moonclicker://<address>/<scriptId>/` 這個 virtual root |
| `src/extension.ts` | `context.subscriptions.push(vscode.workspace.registerFileSystemProvider("moonclicker", provider, { isCaseSensitive: true }))`；移除 `pullCommand`/`pullRemoteCommand`/`pushCommand`/`pushAndRunCommand`；`runRemoteCommand`／`pushAndRun` 合併成單一「Run」command，對著目前開啟的 virtual workspace folder 觸發裝置端 `/run`（不用先 push，因為寫入已經即時同步） |
| `package.json` | 指令清單移除 `moonclicker.pull`、`moonclicker.push`、`moonclicker.pullRemote`；F5 keybinding 條件從 `resourceFilename == main.lua` 改成額外判斷 scheme（local 與 virtual 都要能 F5） |
| `src/luarc.ts` | 不變。LuaLS 是原生行程，透過 `furi.decode(uri)` 把 LSP 給的 URI 解成 OS 路徑後直接 `io.open`，完全不經過 VS Code 的 `FileSystemProvider`（查證：`sumneko.lua` 的 `server/script/brave/work.lua` `loadFile` handler、`package.json` 未宣告 `capabilities.virtualWorkspaces`）。因此 virtual 資料夾放棄型別提示（E2 已定案），`setupStubs` 指令在 active workspace folder 是 `moonclicker:` scheme 時直接 disable／提示「僅支援本機資料夾」 |
| `src/scriptArchive.ts`（新，選配） | 沿用既有 `adm-zip` 依賴，在擴充套件端用 `getTree` + `readFile` 組出 zip（匯出備份用），`extractAllTo` 逐檔案 `writeFile`/`mkdir`（還原用）。純粹是本機端的便利工具，跟裝置端 API 無關，裝置端不需要為此開任何 zip route |

## D. 移除的功能

| 現有功能 | 處置 |
|---|---|
| `moonclicker.pull` / `pullRemote` | 移除，virtual FS 直接瀏覽取代 |
| `moonclicker.push` / `pushAndRun` | 移除，寫入即同步；`pushAndRun` 併入新的 Run |
| Local Scripts 樹狀節點（`LocalScriptItem`、本機資料夾瀏覽） | 移除，Workspace 只剩「已連線裝置的 virtual 資料夾」 |
| `moonclicker.renameScript` | 保留，但行為改成呼叫裝置端改 scriptId／display name 的 API（不是檔案 rename，這是另一件事，跟 provider.rename 分開） |

## E. 已定案的問題

1. **Zip**：裝置端 `/export`、`/import` HTTP route 移除；`ScriptArchive.kt` 本身留著給 App 內建功能用。VS Code 端若要備份/還原，改在擴充套件內用新的細粒度 API 自己組 zip（見上表 `scriptArchive.ts`），不需要裝置端配合。
2. **LuaLS 型別提示**：virtual 資料夾放棄，`setupStubs` 只對本機 pull 下來的資料夾生效。根因是 LuaLS 為原生行程、直接對解碼後的 OS 路徑做 `io.open`，不經過 `vscode.FileSystemProvider`，沒有繞過空間（查證見上表 `luarc.ts` 列）。
3. **外部變更偵測**：裝置端加 `FileObserver`/`WatchService`，外部（檔案管理員、USB）改動也會推播 `file_change`（見 B 表）。
