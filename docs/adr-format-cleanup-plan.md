# ADR 0006–0013 回歸模板：修改提案（v2，已納入裁示）

**這是一份過渡文件。** 執行完 step (2) 之後刪除，不要讓它留在 repo 裡。

`ADR-FORMAT.md` 的模板是「標題 + 1-3 句話」，可選 `Status`／`Considered Options`／
`Consequences`。`0001`–`0005`（7–9 行）符合；`0006` 起漂移到 15→21→11→33→34→32→78→72 行。

本文件只處理 **ADR 本身**。壓縮時不考慮有誰引用它——引用配合 ADR，不是反過來。修復引用是
step (2)。

## 已定案的裁示

1. **0006 直接移除**（不改標題、不改檔名）
2. **0009 改標題**：拿掉 `ADR-0009:` 前綴，與其餘篇章一致
3. **0007 的「Simple Script 錄製/編輯無替代方案」直接刪除**，不開 issue
4. **0012 的改動排在 step (2) 之後**
5. **0007 只保留結論**
6. **0009 移除 `Considered Options`**
7. **0013 只保留結論**：描述該怎麼做，不敘述舊版本的狀況

裁示 7 是一條通則，因此**同樣套用到 0010 與 0011**——兩篇原本都以「v2 的……」開場。
若你只想套在 0013，說一聲。

## 一條硬規則

**每一段被刪的文字都必須有指名的去處。** 沒有去處就不刪。「刪除」必須附理由。

## 總覽

| ADR | 現況 | 提案 | 主要動作 |
|---|---|---|---|
| 0006 | 15 | **移除** | 活內容已在 `CONTEXT.md:58/:62/:66` |
| 0007 | 21 | ~9 | 只留結論；行程存活陷阱移進程式碼 |
| 0008 | 11 | ~9 | **建議保留**（見下） |
| 0009 | 33 | ~9 | 改標題；兩個否決理由移進 build 腳本註解 |
| 0010 | 34 | ~11 | 只留結論；Stopped/Error 承諾移進測試 |
| 0011 | 32 | ~9 | 只留結論；兩節已在 `lua-api.md` |
| 0012 | 78 | ~9 | 護欄移進程式碼；一節已過期 |
| 0013 | 72 | ~9 | 只留結論；兩個替代方案移進程式碼 |

296 → ~65 行。

## 編號不重排

ADR 編號是**只增不改的帳本**。0006 移除後編號留空，`0007` 起不往前遞補。commit message 與
既有引用因此全部維持有效。

---

## 0006 — 移除

活內容 `:engine` 是碰原生內部的唯一邊界，已完整記載於 `CONTEXT.md:58`（邊界本身）、
`:62`（`EngineStateRepository` 範圍）、`:66`（`LuaEngineControl` facade）。ADR 本文其餘部分
是一個已經半數解體的四模組切分（`:overlay`、`:simplescript` 皆不存在）。

**要修的 8 處引用**（step (2)）：

| 位置 | 處理 |
|---|---|
| `CONTEXT.md:58` | 刪連結，事實本身留著 |
| `CONTEXT.md:62` | 刪連結；併入「為何未做 per-VD 狀態」一句（原 0006 Scope note） |
| `CONTEXT.md:66` | 刪連結 |
| `docs/adr/0002` Status | 刪連結，改指 `CONTEXT.md` |
| `docs/adr/0009` Status | 隨 0009 改寫一併移除 |
| `docs/adr/0012` Status | 「補充 ADR-0006」改為直述 |
| `docs/adr/0007` ¶3 | 隨 0007 壓縮一併消失 |
| `docs/adr/0008` ¶2 | 隨 0008 壓縮一併消失 |

> `603d1ec feat: Wire ScriptCompiler output into NativeLuaScriptRunner (ADR-0006 follow-up)`
> 會指向不存在的 ADR。接住它的是 **0008**——0008 本文記著那個功能在刪除當下仍是活的。
> 這是「刪 0006、留 0008」必須成對的原因。

## 0007（21 → ~9）

````md
# Drop the Lua-driven Overlay UI platform in favour of a status Notification

ReLC is not a platform for scripts that define their own UI. The overlay window mechanism,
the engine-side `ui` Lua table and the `on_event` callback are removed, and run status lives
in a plain system Notification that needs no window permission at all. The overlay was never
core to what ReLC does — run scripts against a virtual display — and it cost a bespoke JSON
UI schema plus a second Compose rendering path.

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1 決定與理由 | 留下，改為現在式結論 |
| ¶2 被移除的類別清單 | **刪除** — git 有完整紀錄 |
| ¶3 `OverlayContentExtension` 之死 | **刪除** |
| ¶3 尾「Simple Script 錄製/編輯無替代方案」 | **刪除**（裁示 3） |
| ¶4 引擎側移除、`app.set_data` 保留 | **刪除** — API 現況以 `docs/lua-api.md` 為準 |
| `Scope note` ¶1 `ScriptManager` 未受影響 | **刪除** |
| `Scope note` ¶2 **行程存活** | **移進程式碼註解**（`ScriptStatusNotifier.kt`）→ step (2) |
| `Scope note` ¶3 `POST_NOTIFICATIONS` | **刪除** — 程式碼可見 |

> ⚠️ 「移除的 AccessibilityService 原本順帶讓行程活著，`setOngoing(true)` 不會，所以長時間
> 執行的腳本現在會在使用者切到別的 app 時被殺掉」**不是舊版本的狀況，是現在出貨的行為**。
> 它不該憑空消失。`ScriptStatusNotifier.kt:21` 本來就引用著 ADR-0007，那裡是它的家。
> **若你要連這段一起刪，請明示**——這會讓一個已知的行為退化失去書面紀錄。

## 0008（11 → ~9）— 建議保留

````md
# Remove the :simplescript module entirely, rather than keep it as a dormant future-execution target

`:simplescript` (Room-backed script model, its editor screens, `ScriptCompiler`, the
"Simple V2" tab) is deleted wholesale rather than kept dormant, even though at the moment of
deletion it was a working feature and not dead code. Its only entry points were the removed
Overlay UI ([ADR-0007](0007-drop-lua-overlay-ui-for-status-notification.md)) and an embedded
editor whose host screen is itself being replaced
([issue #5](https://github.com/kuomartin/ReLC/issues/5)); carrying a second parallel
script-authoring stack through that rework was judged not worth the cost.

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1 刪除範圍 | 留下，壓縮 |
| ¶2 「deliberately blunt call」 | 併進決定句 |
| ¶2 尾「選擇/執行腳本沒有替代 UI」 | **刪除** — issue #5 已追蹤 |
| ¶3 `app/.../script/simple/*` 同名但無共用程式碼 | **移進程式碼註解** → step (2) |

**為什麼建議留**，對照 `ADR-FORMAT.md` 的三個條件：

1. **Hard to reverse** — 刪掉一整個模組
2. **Surprising** — 「當天上線、當天刪掉一個能動的功能」正是後人會問「為什麼」的事
3. **Real trade-off** — 留著休眠 vs 直接刪，明確選了鈍的那個

模板列的合格類型裡有一條是「Deliberate deviations from the obvious path … These stop the
next engineer from 'fixing' something that was deliberate」——0008 正是這一類。

## 0009（33 → ~9）

````md
# Hidden-API 契約測試獨立成 `:hidden-api-contract` 模組

`:hidden-api` 的 stub 是對平台的假設——某個 `@hide` 成員存在、簽章長某樣——而驗證它們必須
在真機上跨 API level 用反射問平台。這組測試住在一個**只有 androidTest、沒有 main source
set** 的薄模組 `:hidden-api-contract`，Gradle Managed Devices 的 API 矩陣也放這裡。

## Status

Accepted，2026-09-11。相關：[issue #18](https://github.com/kuomartin/ReLC/issues/18)。
````

| 原內容 | 去處 |
|---|---|
| 標題 `ADR-0009:` 前綴 | **刪除**（裁示 2） |
| 狀態/日期/相關 三行 header | **併入 `## Status`**；`ADR-0006` 連結隨 0006 移除 |
| `## 背景`（#16 誤判的往事） | **刪除** — 舊版本狀況，裁示 7 |
| `## 決策` | 留下 |
| 為什麼不放 `:hidden-api`（bootclasspath 會綠燈） | **移進 `hidden-api-contract/build.gradle.kts` 註解** → step (2) |
| 為什麼不放 `:engine`（78.8 MB → 1.7 MB） | **同上** |
| 後果 · `androidTestCompileOnly` 機制 | **同上** |
| 後果 · JLS 13.1 常數 inline、故表用 Java 寫 | **移進 `VirtualDisplayFlagTable.java` 註解** → step (2) |
| 後果 · 以 app UID 執行，只驗存在不驗可呼叫 | **移進 `HiddenApiContracts.kt` 註解** → step (2) |
| 後果 · 新增 stub 要同步加一筆 | **同上** |
| 後果 · 這批慢，分開跑 | **刪除** — build 設定可見 |

> 裁示 6 拿掉 `Considered Options`，但「放進 `:hidden-api` 會拿平台驗平台而且**綠燈**」是整批
> ADR 裡最強的一條反直覺護欄——一定會有人想把測試搬回去。它的去處是
> `hidden-api-contract/build.gradle.kts`：想搬的人就是會看那個檔案。

## 0010（34 → ~11）

````md
# 腳本是線性程式，不是 tick 迴圈

`main.lua` 從上到下執行，跑完就結束；`sleep`、`vision.wait`、`input.*` 直接在腳本自己的
執行緒上阻塞。唯一的回呼是選填且僅呼叫一次的 `on_stop()`，它不構成第二套執行模型——一套就
夠了，而「一步接一步」的流程是自動化的絕大多數，在固定頻率的 tick 迴圈裡反而得自己維護
狀態機。

## Consequences

持續監控型的自動化要自己寫 `while true` 配 `vision.wait_any`。這被判斷為可接受：那個迴圈是
顯式的、看得懂的。

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1 v2 的兩套模型 | **刪除** — 舊版本狀況，裁示 7（理由壓成決定句的尾巴） |
| ¶2 v3 只留線性模型 | 留下，改為現在式 |
| `## 實作上的簡化` · coroutine 拿掉、condition variable | **刪除** — 實作細節 |
| `## 實作上的簡化` · 主動停止回報 `Stopped` 而非 `Error` | **移進測試註解**（`LuaScriptLifecycleTest.kt`）→ step (2)。行為契約，該測試正在驗它 |
| ¶ 比對改成隨需執行 | **刪除** — 已由 ADR-0013 與 `CONTEXT.md` 承載 |
| `## 代價` | **升為 `Consequences`** — 是現在式後果，不是舊版敘事 |

## 0011（32 → ~9）

````md
# 腳本不擁有顯示器的生命週期

目標顯示器由 `:app` 在啟動時決定並注入，引擎只把自己取影格用的 surface 掛上去、結束時拿掉
——**顯示器本身不動**。Lua API 因此沒有 `display.create`／`display.launch`／`display.get_all`：
Displays 頁是顯示器生命週期的唯一擁有者，兩個互不知情的擁有者只會互相收掉對方的顯示器。

## Status

Accepted。取代 [ADR-0002](0002-lua-as-scripting-engine.md) 所描述 API 面貌中的 `display.*`
部分；Lua 作為腳本語言的決定本身不變。
````

| 原內容 | 去處 |
|---|---|
| ¶1 v2 的兩個擁有者互相踩 | **壓成決定句尾的一個子句** — 裁示 7 |
| ¶2 v3 的做法 | 留下，改為現在式 |
| `## 隨之而來的約束：實體螢幕沒有畫面辨識` | **刪除** — `docs/lua-api.md:48/:70/:188` 已完整記載（已驗證） |
| `## script.json 存的是尺寸，不是 displayId` | **待確認** — `lua-api.md` 是否已載明 `script.json` 的 `display` 欄位語意？未載明則移進該文件 → step (2) |

## 0012（78 → ~9）— 排在 step (2) 之後

````md
# Surface 尺寸由服務擁有，不由呼叫端回推

`getDisplaySize` 回的是**邏輯**尺寸（旋轉 90/270 時長寬互換），但 `AImageReader` 必須以
虛擬顯示**建立時**的 surface 尺寸開。呼叫端不從（邏輯尺寸, rotation）回推——那是兩次獨立
讀取，中間畫面轉了就會算出一組看起來合理、實際不自洽的答案，而影格緩衝區的長寬比一錯就錯到
腳本結束。`vdStore` 存 `ManagedDisplay(display, surfaceWidth, surfaceHeight)`，
`getDisplaySurfaceSize` 直接回建立時的常數；服務沒建過的顯示器回 `[0, 0]` 讓呼叫端當場失敗，
而不是帶著可能錯的尺寸跑完整場。

## Status

Accepted。與 [ADR-0011](0011-scripts-do-not-own-displays.md) 一致——腳本不擁有顯示器，
也就不該是知道它幾何的人。
````

| 原內容 | 去處 |
|---|---|
| ¶1–3 競態與危害 | 壓進決定句 |
| `## 決定` 四個 bullet | 壓進決定句 |
| ¶ `DisplayGeometry.surfaceSize` 留著 | **刪除** — 程式碼可見 |
| `## 沒有新測試，這是刻意的` | **刪除** — 關於「這次變更」的後設評論 |
| `## 名字與旋轉不記在這裡` · 名字 | **刪除** |
| `## 名字與旋轉不記在這裡` · 旋轉是活狀態 | **併入 CONTEXT.md** → step (2) |
| `## rotation 讀兩次是對的，不要合併` | **移進程式碼註解**（`ScriptEngine.kt:112`、`DisplayRotationTracker.kt:31`）→ step (2) |
| `## 已知的同類問題（另案處理）` | **刪除 — 已過期**。commit `4f93246` 已修，`matchesSize` 現在直接比對 `getDisplaySurfaceSize`（已驗證 `ScriptSession.kt:158-161`） |

> ⚠️ **本檔最高風險項**。`## rotation 讀兩次是對的，不要合併` 自己記載著這份知識**已經遺失
> 過一次**。它必須先落地到那兩個程式碼註解，**才能**從 ADR 刪除。

## 0013（72 → ~9）

````md
# 模板圖是邏輯空間的產物

腳本作者提供的模板就是他在畫面上看到的方向，引擎負責把它轉到影格的方向再比對——
`TM_CCOEFF_NORMED` 不是旋轉不變的，未經轉換的模板在顯示器旋轉後就比不中。這是對外契約而非
實作細節：對腳本而言 surface 空間不存在，座標、`roi`、模板三者都在邏輯空間。無條件，不提供
`rotate = false` 之類的逃生門。

## Status

Accepted。
````

| 原內容 | 去處 |
|---|---|
| `## 脈絡`（承諾有個洞、沒測試覆蓋才沒發現） | **刪除** — 舊版本狀況，裁示 7；理由壓成決定句的破折號子句 |
| `## 決定` | 留下 |
| `## 目前的實作機制（不凍結）` · `templateFor`、快取鍵、方向推導 | **移進程式碼註解**（`VisionMatcher.cpp`，該處已有半段）→ step (2) |
| `## 目前的實作機制` · 轉模板 vs 轉影格成本表 | **同上** — 連同「成本反轉時換過去不違反本 ADR」那句 |
| `## 不在 GlesDistributor 轉` 三個理由 | **同上** |
| `## 後果` · 一張模板服務所有方向 | **刪除** — 決定本身的重述 |
| `## 後果` · scale 不是尺度不變 | **刪除** — `docs/lua-api.md:38` 已載明（已驗證） |
| `## 後果` · 快取條目數 ×4 | **刪除** — 作者自評「可忽略」 |
| `## 後果` · `Tier1SpikeTest` 須保留對稱與不對稱兩圖樣 | **移進測試註解**（`Tier1SpikeTest.kt`）→ step (2)。少了它，旋轉方向寫反不會有測試失敗 |

---

## 仍待確認

1. **0008 保留還是移除？** 我建議保留（理由見該節）。
2. **0007 的行程存活陷阱**：移進 `ScriptStatusNotifier.kt` 註解，還是連同一起刪？
   我建議前者——那是現在出貨的行為。
3. **裁示 7 是否套用到 0010、0011？** 本提案已套用。
4. **0011 的 `script.json` 存尺寸**：確認 `lua-api.md` 是否已載明。

## 不在本文件範圍

- commit message：**永不修改**（已推送、全部以編號引用）
- 修復指向 ADR 內部或指向被刪 ADR 的引用
- 上表所有標記「→ step (2)」的新註解與文件

以上全部是 step (2)。
