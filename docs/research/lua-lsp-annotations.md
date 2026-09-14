# Script Workbench 的 LuaLS 型別提示：annotation stub 怎麼生、怎麼掛

Researched 2026-09-15 against the repo at commit `20eae92`（worktree
`agent-ae1eafcdf18d139a4`）。針對 #48（隸屬 #47）。方法：讀
`engine/src/main/cpp/LuaBindings.cpp` / `.h`、`LuaEngine.h` / `.cpp`，讀
`docs/lua-api.md`（v3）與 `docs/adr/0005-lua-api-doc-is-conceptual-not-frozen.md`，
查 LuaLS（LuaLS/lua-language-server）官方 wiki 與 GitHub repo 作為 annotation
語法與 `.luarc.json` 設定的一手來源。

## 1. Summary（結論先行）

- **Annotation 語法**：LuaLS 吃的是一套叫 LuaCATS 的 `---@` 註解，寫在純
  `.lua` 檔裡（不含實作，只宣告型別），檔案開頭加 `---@meta` 標成定義檔。
  本案用得到的核心標籤是 `---@class`、`---@field`、`---@param`、
  `---@return`、`---@overload`；細節與來源見第 2 節。
- **產生方式**：`LuaBindings.cpp` 的綁定是規則的（`luaL_Reg` 陣列 +
  `lua_pushcclosure`），但**型別資訊不在 C++ 裡**——每個 binding 函式都是手動
  `luaL_checknumber` / `lua_getfield` 解堆疊，沒有結構化到能自動抽出參數型別
  或 table 形狀的程度。結論：**手寫維護一份 stub，不做生成器**；`docs/lua-api.md`
  雖然是散文/表格、人工轉譯容易，但依 ADR-0005 它本來就不是凍結契約、已知會漂移
  （`docs/research/vision-ocr-status.md` 記錄過一次），拿它當生成來源只是把
  「文件會漂移」換成「文件漂移時 stub 也跟著漂移」，沒有解決問題。真正的護欄是
  一個輕量 CI 檢查：`LuaBindings.cpp` 有變動但 stub 檔沒變動就讓 PR 失敗——這用
  `git diff --name-only` 比對兩個路徑就做得到，不需要真的解析 C++。
- **交付到使用者專案**：**stub 隨 VS Code extension 一起發布**（bundled 在
  extension 套件內），extension 在啟動時確保使用者開啟的腳本資料夾有
  `.luarc.json` 指向 extension 自己內建的 stub 目錄；**不要**讓裝置在每次連線同步
  時把 stub 檔案寫進使用者的 script 資料夾。理由見第 4 節：extension 版本應該是
  使用者看到的型別提示的權威來源，裝置韌體版本不該覆寫它，而且同步進 script
  資料夾會污染使用者的 git 版控與工作區。

---

## 2. Annotation 格式：LuaLS 吃什麼

一手來源：LuaLS 官方 wiki（[luals.github.io/wiki/annotations](https://luals.github.io/wiki/annotations/)，
鏡像自 [github.com/LuaLS/lua-language-server/wiki](https://github.com/LuaLS/lua-language-server/wiki)）。

LuaLS 使用的註解格式稱為 **LuaCATS**（Lua Comment And Type System），全部寫在
`---` 開頭的行內，直接放在純 `.lua` 檔裡——這種檔案可以完全沒有函式實作，只有
宣告，這正是我們要的「stub / meta 定義檔」型態。本案用得到的標籤：

| 標籤 | 語法 | 用途 |
|---|---|---|
| `---@meta [name]` | 檔案第一行 | 標記整個檔案是定義檔，不是真的要執行的程式；LuaLS 據此把它當 library 而非工作區腳本處理 |
| `---@class [(exact)] <name>[: <parent>...]` | 型別/表宣告開頭 | 宣告一個表/物件型別，例如 `vision`、`vision.Request` |
| `---@field [scope] <name[?]> <type> [description]` | 緊跟在 `---@class` 之後 | 宣告該 class 的欄位，`?` 表示可為 nil |
| `---@param <name[?]> <type[|type...]> [description]` | 緊跟在函式宣告前 | 宣告函式參數型別，`?` 表示選填 |
| `---@return <type> [<name> [comment]]` | 緊跟在函式宣告前 | 宣告回傳型別，可多個 `---@return` 表示多回傳值 |
| `---@overload fun([param: type...]): [return_value...]` | 緊跟在函式宣告前 | 宣告額外的呼叫簽名（例如 `vision.find` 接受 string 簡寫或 table request 兩種呼叫方式） |

範例（LuaLS wiki 給的形式，語法本身是一手引用，內容為本研究改寫以貼近本案）：

```lua
---@meta

---@class VisionRequest
---@field image string
---@field threshold number?

---@class vision
local vision = {}

---@param request VisionRequest|string
---@return table? hit
function vision.find(request) end
```

這正好對應 `docs/lua-api.md` 裡 `vision.find(request)` 「只給圖片路徑時可以簡寫成字串」
（`docs/lua-api.md:91`）的雙簽名寫法，用 `---@overload` 或 `type|type` 聯集都能表達。

Source: [LuaLS Wiki — Annotations](https://luals.github.io/wiki/annotations/)。

### `.luarc.json` 怎麼把 stub 目錄接進使用者專案

一手來源：[LuaLS Wiki — Configuration File](https://github.com/LuaLS/lua-language-server/wiki/Configuration-File)、
[LuaLS Wiki — Settings](https://luals.github.io/wiki/settings/)、
[LuaLS Wiki — Addons](https://luals.github.io/wiki/addons/)。

- LuaLS（VS Code 端由官方 `sumneko.lua` / 現在的 `LuaLS.lua` extension 承載）在
  workspace 根目錄找 `.luarc.json`；設定載入順序中它排在 `--configpath` 之後、
  且「不需要 `Lua.` 前綴」——VS Code 的 `settings.json` 用 `Lua.workspace.library`，
  `.luarc.json` 直接寫 `workspace.library`。
- 最小可行設定：
  ```json
  {
    "workspace.library": ["path/to/library/directory"]
  }
  ```
  `workspace.library` 接受一個目錄路徑陣列；LuaLS 會把該目錄下所有 `.lua`
  檔當作額外的 library 來源，供工作區內其他 `.lua` 檔案做自動完成／hover／
  型別檢查用，不會把這些檔案當成「要執行的腳本」列進診斷（因為它們有
  `---@meta`）。
- 另有一套**正式的 addon 機制**（`workspace.userThirdParty` + 每個 addon 自帶
  `library/` 子目錄與 `config.json`）：把一個「addon 集合目錄」設進
  `workspace.userThirdParty`，LuaLS 掃到後會提示使用者「要不要啟用這個
  addon」，啟用後才等效於把它的 `library/` 併入 `workspace.library`。這套機制
  是給「第三方套件作者發布共用型別定義、多個使用者專案共享」設計的，多一層
  UI 提示與 `config.json` 中繼資料。

  對本案（一個 extension 只服務自己的一種 API）沒必要：我們不需要「使用者
  被詢問要不要啟用」這一步，直接寫 `workspace.library` 指到 extension 內建的
  絕對路徑，開箱即用。**結論：用 `workspace.library`，不用 addon manager
  機制**——`workspace.userThirdParty` 那一層 UI 提示是為了讓使用者在多個候選
  第三方定義檔之間做選擇，我們只有一份、非選擇性，加這層只會多一次不必要的
  「Enable this addon?」互動。

Sources:
[LuaLS Wiki — Configuration File](https://github.com/LuaLS/lua-language-server/wiki/Configuration-File),
[LuaLS Wiki — Settings](https://luals.github.io/wiki/settings/),
[LuaLS Wiki — Addons](https://luals.github.io/wiki/addons/)。

---

## 3. 產生/維護方式：從哪裡生 stub，怎麼防止它凍結

### 3.1 `LuaBindings.cpp` 對機械抽取而言夠不夠規則

讀完整個 `engine/src/main/cpp/LuaBindings.cpp`（565 行）：

- **註冊部分高度規則**：所有函式都用 `const luaL_Reg kXxx[] = {{"name", fn}, ...}`
  陣列宣告（`LuaBindings.cpp:499-534`），再透過 `registerModule()`
  （`LuaBindings.cpp:488-497`）統一掛進全域表。`screen` 是唯一例外，用
  `__index` metatable 動態計算唯讀欄位（`LuaBindings.cpp:214-233`、
  `550-556`），`log`/`sleep` 是不掛表、直接 `lua_setglobal` 的全域函式
  （`LuaBindings.cpp:540-547`）。這一層——「有哪些模組、每個模組有哪些函式
  名字」——抽取難度低，掃 `luaL_Reg` 陣列加上兩三個特例就能拿到完整函式清單。
- **簽名/型別資訊不規則、且不在型別系統裡**：每個綁定函式內部用
  `luaL_checknumber`、`luaL_optinteger`、`lua_getfield`、`lua_istable` 等
  API 手動從 Lua 堆疊讀值（例如 `lua_input_tap`，`LuaBindings.cpp:337-347`；
  `readRequest`，`LuaBindings.cpp:74-112`），C++ 這一側完全沒有型別標註或
  一致的宣告巨集能讓 script 抽出「這是第幾個參數、型別是什麼、選填與否」。
  想要機械抽取，勢必得在每個綁定函式上方加一組結構化 C++ 註解慣例（類似
  Doxygen，但要另外定義一套能表達 Lua 型別的 tag），這是額外的維護面——
  等於在 C++ 側和 stub 側各維護一份型別描述，只是把「stub 會漂移」換成
  「C++ 註解會漂移」，沒有降低成本。
- **`docs/lua-api.md` 相對好解析**：它已經是人工整理過的表格/程式碼範例
  （見 `docs/lua-api.md` 全文），比 C++ 堆疊操作更接近 stub 的最終形態。但
  ADR-0005 明講它「不是凍結的相容性契約」，而且 `docs/research/vision-ocr-status.md`
  記錄過真實案例：v2 時期 `input.tap` / `input.swipePolyline` 等文件裡的簽名
  與當時引擎實際綁定的函式對不上。拿一份「已知會漂移、而且漂移沒有訊號」
  的文件當生成來源，得到的 stub 一樣會漂移，只是漂移的位置從「手寫 stub」
  搬到「生成器輸入」，risk 沒有消失。

**結論**：不寫生成器。`LuaBindings.cpp` 沒有規則到能省下多少工，
`docs/lua-api.md` 規則但不可信。手寫一份 stub（`.lua` + LuaCATS 註解），
跟著 `LuaBindings.cpp` 的 PR 一起手動更新，比維護一個「輸入不可信」或
「需要額外 C++ 註解慣例」的生成器便宜。

### 3.2 怎麼讓 stub 不要靜靜地凍結過時

ADR-0005 的教訓是「文件沒有凍結但也沒人逼它跟上」。對 stub，能做但成本不同的
選項：

- **完全沒有護欄**：跟 `docs/lua-api.md` 現況一樣，靠人自覺更新。成本最低，
  但正是 ADR-0005 已經證明會失敗的模式（v2→v3 之間 `input.*` 就對不上，見
  `docs/research/vision-ocr-status.md` 第 3 節）。不推薦當唯一防線。
- **推薦：一個簡單的 CI path-pair 檢查**——PR 改到
  `engine/src/main/cpp/LuaBindings.cpp` 或 `LuaBindings.h`，但同一個 PR 沒有
  改到 stub 檔案（例如未來的 `tooling/vscode-extension/stubs/relc.lua`），
  就讓 CI 失敗，訊息提示「LuaBindings 變了，記得檢查 stub 是不是也要更新」。
  這只是 `git diff --name-only origin/main... | grep` 兩個路徑的存在與否，
  不需要解析 C++ 或 Lua，一兩個 shell 指令 + CI job 就能做，成本比生成器低
  一個數量級。它不保證 stub 的內容正確（沒人強制檢查「改了什麼」跟「stub
  改得對不對」的語意對應），但它把「忘了改」變成「PR 過不了」，這是
  `docs/lua-api.md` 從來沒有過的訊號。
- **更嚴格但更貴的選項（不建議現在做）**：寫一個真的比對 `luaL_Reg` 函式名
  清單與 stub 裡 `---@class`/函式宣告名稱是否一致的 script（純名稱比對，
  不比型別）。比 path-pair 檢查誠實一點（真的抓得到「加了新函式忘記寫進
  stub」），但要寫一個小型 C++ 或 Lua 解析器，維護成本明顯上升，且對「改了
  既有函式的參數語意但沒加新函式」這種最常見的漂移一樣抓不到。現階段
  path-pair 檢查的成本/效益比更合理；等 stub 檔案數量變多、function 清單
  比對這步真的划算時再加這層。

**結論**：用 path-pair CI 檢查當唯一的自動化護欄，誠實承認它只防「忘了碰」，
不防「碰了但碰錯」——跟人工 review 分工，不假裝自動化解決了語意正確性。

---

## 4. 交付到工作區：stub 怎麼進到使用者的 VS Code 專案

三個選項（issue 原文列的方向）：

- **(a) Extension bundle**：stub `.lua` 檔打包進 VS Code extension 本身，
  extension 在 activate 時確保使用者開啟的腳本資料夾有 `.luarc.json`，指向
  extension 安裝目錄下的 stub 路徑（VS Code extension 的安裝目錄路徑在
  activate 時可以透過 `context.extensionPath` 這類 API 取得絕對路徑，寫進
  `workspace.library`）。
- **(b) 裝置推送**：ReLC 裝置端在 Workbench 每次連線同步腳本資料夾時，
  順便把一份 stub 檔案也同步進使用者的 script 資料夾。
- **(c) Hybrid**：兩者都做。

### Tradeoff

| 面向 | (a) Extension bundle | (b) 裝置推送進同步資料夾 |
|---|---|---|
| 版本以誰為準 | Extension 版本（VS Code Marketplace 更新頻率、使用者主動升級） | 裝置韌體版本（每台裝置可能停在不同韌體） |
| 離線可用性 | 好——stub 隨 extension 一起裝好，不連裝置也有型別提示 | 差——沒連過裝置的新專案完全沒有 stub，得先連一次線才有提示 |
| 使用者工作區/git 污染 | 無污染——stub 活在 extension 安裝目錄，不進使用者的 script 資料夾、不會被使用者的 git 追蹤 | 有污染——stub 檔案混進使用者要 commit 的 script 資料夾，除非額外教使用者寫 `.gitignore`，且每次同步都可能造成「這個檔案又被裝置改了」的 diff 噪音 |
| Multi-root workspace | 乾淨——一個 extension 全域或依 workspace 寫一份 `.luarc.json` 指向同一個 bundled 路徑，行為可預期 | 每個裝置各自推一份可能造成同一個 workspace 下多個資料夾各有一份（版本可能還不一樣的）stub，`workspace.library` 疊加後容易出現同名 `---@class` 衝突 |
| 「script 作者連的裝置韌體版本 vs 他裝的 extension 版本」不一致時 | Extension 版本贏——型別提示反映「Workbench 這個版本認得的 API」，如果裝置韌體比較舊，使用者會看到提示了引擎實際上還沒有的函式（風險：提示與實跑行為不符） | 裝置版本贏——型別提示準確反映這台裝置的實際 API，但使用者換一台裝置連線就換一份 stub，同一個專案在不同時間點會有不同的自動完成結果，體感不穩定 |

### 建議：(a) Extension bundle，不做 (b)

理由：

1. **穩定性優先於裝置精確性**：Script Workbench 是一個開發時工具，使用者
   會頻繁在離線狀態下寫腳本、只有偶爾要跑真機測試才連裝置。型別提示的
   價值在「寫的當下就知道自己打錯字/少填參數」，這件事不該依賴裝置在線。
   Extension bundle 是唯一能滿足離線可用性的選項。
2. **裝置推送本質上是把「同步腳本」跟「同步型別定義」兩個目的混在同一個
   管道**，會把 stub 檔案變成使用者 script 資料夾裡的常駐檔案，造成 git
   污染與 multi-root 下的疊加衝突（見上表）；(b) 沒有任何一項贏過 (a)，
   純粹是額外的複雜度來源，不建議做，也不建議做 hybrid——hybrid 只是把
   (b) 的所有缺點原封不動保留、只多了「兩份 stub 哪份優先」這個新問題。
3. **版本以 extension 為準是可以接受的落差**：使用者升級 extension 才會
   拿到新的型別提示，跟裝置韌體版本暫時不同步是正常的、可預期的落差
   （跟任何 IDE 外掛版本落後於實際 runtime 版本的情況一樣），比「同一個
   workspace 因為連過不同裝置而出現不一致的自動完成」容易理解、容易除錯。
4. Extension activate 時寫 `.luarc.json` 這個動作本身要注意冪等與不覆蓋
   使用者自訂設定：只在該欄位缺漏時補上 `workspace.library` 指向自己的
   bundled 路徑，不要整檔覆寫（使用者可能已經有自己的 `.luarc.json` 設定
   `runtime.version` 之類的東西）。這是實作細節，不影響本節的方向結論。

---

## 5. 給 #47 的具體下一步（若要落地，非本 issue 範圍內的規模估計）

- 建一份 `stubs/relc.lua`（或未來 workbench extension repo 內對應路徑），
  手寫涵蓋 `docs/lua-api.md` v3 的全部模組：`log`/`sleep` 全域、`screen`
  唯讀欄位、`vision`（`VisionRequest`/`VisionHit` 兩個 `---@class` +
  `find`/`wait`/`wait_any`）、`input` 全部函式、`app.launch`、
  `device.notify`/`open_uri`、`data.set`、`on_stop` 生命週期 hook。
- CI 新增一個 job：diff 涉及 `engine/src/main/cpp/LuaBindings.*` 時檢查
  同一個 PR 是否也動到 stub 檔路徑，沒有就失敗並留言提醒（純 path-pair，
  不做語意比對）。
- Workbench extension activate 時的 `.luarc.json` 補寫邏輯，只在缺欄位時
  注入、不覆蓋既有設定。
