# ADR 0006–0013 回歸模板：修改提案

**這是一份過渡文件。** 執行完 step (2) 之後刪除，不要讓它留在 repo 裡。

`ADR-FORMAT.md` 的模板是「標題 + 1-3 句話」，可選 `Status`／`Considered Options`／
`Consequences`，且只在真的有價值時才加。`0001`–`0005`（7–9 行）符合；`0006` 起漂移到
15→21→11→33→34→32→78→72 行。

本文件只處理 **ADR 本身**。壓縮時不考慮有誰引用它——引用配合 ADR，不是反過來。修復引用是
step (2)。

## 一條硬規則

**每一段被刪的文字都必須有指名的去處。** 沒有去處就不刪。去處只有五種：

| 去處 | 什麼東西屬於這裡 |
|---|---|
| 留在 ADR | 決定本身，1-3 句 |
| `Considered Options` | 被否決且否決理由不顯然的替代方案 |
| `Consequences` | 不顯然的下游後果 |
| 程式碼註解 | 「別動這段程式碼，理由如下」的護欄 |
| 刪除 | 已有更合適的家，或已過期 |

「刪除」必須附理由，不能只寫「贅文」。

## 總覽

| ADR | 現況 | 提案 | 主要動作 |
|---|---|---|---|
| 0006 | 15 | ~9 | 兩段已刪除的工作歸入 `Status`；Scope note 併入 CONTEXT.md |
| 0007 | 21 | ~13 | 類別清單刪除（git 有）；行程存活陷阱升為 `Consequences` |
| 0008 | 11 | ~7 | 同名套件護欄移進程式碼 |
| 0009 | 33 | ~13 | 兩個否決理由轉 `Considered Options`；JLS 護欄移進程式碼 |
| 0010 | 34 | ~11 | 實作細節刪除；Stopped/Error 承諾移進測試 |
| 0011 | 32 | ~9 | 兩節已在 `lua-api.md`，刪除 |
| 0012 | 78 | ~9 | 護欄移進程式碼；一節已過期，刪除 |
| 0013 | 72 | ~13 | 兩個替代方案轉 `Considered Options`；成本表移進程式碼 |

合計 296 → ~84 行。

## 檔名一律不動

`CONTEXT.md` 與 ADR 互引用的是 **markdown 連結（檔名）**，改檔名會全部斷掉。編號與檔名都保留。

這帶來一個已知的瑕疵：**0006 的標題點名了 `:overlay` 與 `:simplescript`，兩者都已不存在。**
我的提案是標題與檔名都保留、由 `Status` 承擔這個事實，理由是標題與 slug 應該一致。
→ **待你裁示。**

---

## 0006（15 → ~9）

````md
# Split into :engine/:overlay/:simplescript/:app, with :engine as the sole native-access boundary

`:app` was a single module where the Overlay UI and `FullscreenDisplayActivity` reached
directly into the JNI bridge and a global singleton, with engine state visible only as a
500 ms poll of one boolean. We split it so `:engine` owns the JNI bridge and `RelcV2Service`
as the only module allowed to touch native internals (`LuaNative` is `internal`; others go
through the `LuaEngineControl` facade) and exposes a read-only `EngineStateRepository` fed by
a single `onEngineEvent` upcall, with `:app` as the composition root.

## Status

Accepted, partly superseded by [ADR-0007](0007-drop-lua-overlay-ui-for-status-notification.md)
and [ADR-0008](0008-remove-simplescript-module.md): `:overlay`, `:simplescript`,
`OverlayController` and the `OverlayContentExtension` seam no longer exist, and the
`simplescript.*`-runnable follow-up this ADR once described was deleted the same day it
landed. The `:engine`/`:app` split, `LuaEngineControl` and `EngineStateRepository` are
unaffected.
````

| 原內容 | 去處 |
|---|---|
| ¶1 模組切分 | 留下，壓成一句 |
| ¶2 `simplescript.*` 可執行的後續工作 | **刪除** — 當天就被刪掉的工作，`Status` 已記載此事 |
| ¶3 `OverlayContentExtension` seam | **刪除** — 同上，已隨 0007 消失 |
| `## Scope note`（`EngineStateRepository` 不含 per-VD 狀態） | **併入 CONTEXT.md** — `CONTEXT.md:62` 已陳述此事實，只缺「為何未做」那一句 → step (2) |

> `603d1ec feat: Wire ScriptCompiler output into NativeLuaScriptRunner (ADR-0006 follow-up)`
> 指向的正是被刪的 ¶2。`Status` 保留「deleted the same day it landed」就是為了讓讀到那個
> commit 的人不被誤導。**commit 本身不動。**

## 0007（21 → ~13）

````md
# Drop the Lua-driven Overlay UI platform in favour of a status Notification

ReLC was heading toward a platform where a Lua script defines the whole UI — `ui.*` upcalls
rendering a tree of `DynamicElement`s in an always-on-top window owned by an
AccessibilityService. We abandoned that direction and removed the entire overlay-window
mechanism, including the `:overlay` module and the engine-side `ui` table and `on_event`
callback: the overlay was never core to running scripts against a virtual display, and it
cost a bespoke JSON UI schema plus a second Compose rendering path. Run status now lives in
a plain system Notification, which needs no window permission at all.

## Consequences

The removed AccessibilityService incidentally kept the process alive; a plain
`setOngoing(true)` notification does not, so a long run can now be killed while the user is
in another app. Giving script execution a real process anchor is a separate decision,
deliberately not made here.

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1 決定與理由 | 留下 |
| ¶2 被移除的類別清單 | **刪除** — git 有完整紀錄；「改用 Notification、免權限」併進決定句 |
| ¶3 `OverlayContentExtension` 之死 | **刪除** — 0006 的 `Status` 已記載 |
| ¶3 尾「Simple Script 錄製/編輯沒有替代方案」 | **待裁示**（見下） |
| ¶4 引擎側移除、`app.set_data` 保留 | **刪除** — API 現況以 `docs/lua-api.md` 為準 |
| `Scope note` ¶1 `ScriptManager` 未受影響 | **刪除** — 決定句已限定範圍 |
| `Scope note` ¶2 行程存活 | **升為 `Consequences`** — 不顯然且是真的陷阱，模板明文允許 |
| `Scope note` ¶3 `POST_NOTIFICATIONS` | **刪除** — 程式碼可見 |

> **待裁示**：「拖曳浮動標記錄製 Simple Script 現在沒有替代方案」是一個**活的功能缺口**。
> 它不是決策紀錄，放在 ADR 裡等於沒有人會追。建議開 issue 承接後從 ADR 刪除。

## 0008（11 → ~7）

````md
# Remove the :simplescript module entirely, rather than keep it as a dormant future-execution target

The `:simplescript` module (Room-backed script model, its editor screens, `ScriptCompiler`,
and the "Simple V2" tab) is deleted wholesale rather than kept dormant. It was a working
feature at the moment of deletion, not dead code, but its only entry points were the removed
Overlay UI ([ADR-0007](0007-drop-lua-overlay-ui-for-status-notification.md)) and an embedded
editor whose host screen is itself being replaced by the Scripts/Displays navigation rework
([issue #5](https://github.com/kuomartin/ReLC/issues/5)) — carrying a second parallel
script-authoring stack through that rework was judged not worth the cost, and there were no
real user scripts to migrate.

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1 刪除範圍 | 留下，壓縮 |
| ¶2 「deliberately blunt call」的理由 | 併進決定句 |
| ¶2 尾「選擇/執行腳本沒有替代 UI」 | **刪除** — issue #5 已追蹤該改版 |
| ¶3 `app/.../script/simple/*` 未受影響、同名但無共用程式碼 | **移進程式碼註解** — 這是防止誤刪的護欄，屬於程式碼 → step (2) |

## 0009（33 → ~13）

````md
# Hidden-API 契約測試獨立成 `:hidden-api-contract` 模組

`:hidden-api` 的 stub 是對平台的假設——宣告某個 `@hide` 成員存在、簽章長某樣——而這些假設
過去只靠人工比對 AOSP 驗證，[#16](https://github.com/kuomartin/ReLC/issues/16) 就曾經誤判
「API 27–29 沒有 `freezeDisplayRotation`」。我們新增一個**只有 androidTest、沒有 main
source set** 的薄模組 `:hidden-api-contract`，跨 API level 用反射驗證它們，Gradle Managed
Devices 的矩陣也放這裡。

## Considered Options

- **放 `:hidden-api`**：不可行。androidTest 會把 stub 打包進 test APK，但 ART 解析
  `android.*` 時 **bootclasspath 永遠優先**，載入的仍是平台真類別——測試等於拿平台驗平台，
  而且會**綠燈**。看起來有在驗證、實際什麼都沒驗，比沒有測試更危險。
- **放 `:engine`**（[#18](https://github.com/kuomartin/ReLC/issues/18) 原本的提議）：契約
  測試對 `:engine` 零引用，卻會連帶跑 CMake／OpenCV 的 native build，四個 ABI 全編，
  test APK 實測 **78.8 MB**；抽出來之後是 **1.7 MB**，而矩陣裡每一台裝置都要重跑一次。

## Status

Accepted，2026-09-11。相關：[#18](https://github.com/kuomartin/ReLC/issues/18)、
[ADR-0006](0006-module-split-and-engine-facade.md)。
````

| 原內容 | 去處 |
|---|---|
| 標題 `ADR-0009:` 前綴 | **刪除** — 其餘七篇都沒有 → **待裁示**（純美觀） |
| 狀態/日期/相關 三行 header | **併入 `## Status`** — 與 0001–0005 一致 |
| `## 背景` | 壓成決定句的前半 |
| `## 決策` | 留下 |
| 為什麼不放 `:hidden-api` | **`Considered Options`** — bootclasspath 那點極不顯然，模板明文允許 |
| 為什麼不放 `:engine` | **`Considered Options`** — 78.8 MB → 1.7 MB 是決定性數字 |
| 後果 · `androidTestCompileOnly` 機制 | **移進程式碼註解**（`hidden-api-contract/build.gradle.kts`）→ step (2) |
| 後果 · JLS 13.1 常數 inline、故表用 Java 寫 | **移進程式碼註解**（`VirtualDisplayFlagTable.java`）→ step (2)。**最高價值的護欄**：常數錯不會有任何聲音 |
| 後果 · 以 app UID 執行，只驗存在不驗可呼叫 | **移進程式碼註解**（`HiddenApiContracts.kt`）→ step (2) |
| 後果 · 這批慢，分開跑 | **刪除** — build 設定可見 |
| 後果 · 新增 stub 要同步加一筆 | **移進程式碼註解**（`HiddenApiContracts.kt`）→ step (2) |

## 0010（34 → ~11）

````md
# 腳本是線性程式，不是 tick 迴圈

v2 同時提供固定頻率的 `on_tick` 迴圈與一條會 yield 的 coroutine 主線，於是腳本作者得理解
兩套執行模型，而「一步接一步」的流程——自動化的絕大多數——在 tick 迴圈裡要自己維護狀態機。
v3 只留線性模型：`main.lua` 從上到下執行，跑完就結束，`sleep`／`vision.wait`／`input.*`
直接在腳本執行緒上阻塞；`on_tick`、`on_start`、`config` 全部移除，只保留選填且僅呼叫一次的
`on_stop()`。

## Consequences

持續監控型的自動化現在要自己寫 `while true` 配 `vision.wait_any`。這被判斷為可接受：
那個迴圈是顯式的、看得懂的，而 v2 的 tick 迴圈把它藏成了框架行為。

## Status

Accepted.
````

| 原內容 | 去處 |
|---|---|
| ¶1–2 兩套模型 → 一套 | 留下 |
| `## 實作上的簡化` · coroutine 拿掉、改 `lua_pcall` + condition variable | **刪除** — 實作細節，程式碼即真相 |
| `## 實作上的簡化` · 主動停止回報 `Stopped` 而非 `Error` | **移進測試註解**（`LuaScriptLifecycleTest.kt`）→ step (2)。這是行為契約，該測試正在驗它 |
| ¶ 比對改成隨需執行 | **刪除** — 已由 ADR-0013 與 `CONTEXT.md` 的 VisionMatcher 條目承載 |
| `## 代價` | **升為 `Consequences`** |

## 0011（32 → ~9）

````md
# 腳本不擁有顯示器的生命週期

v2 的腳本用 `display.create` 自己開虛擬顯示、而 `RelcEngine::stop()` 直接銷毀它，與
Displays 頁形成兩個互不知情的擁有者：腳本跑完會收掉使用者建的顯示器，使用者關掉顯示器則會
讓腳本的畫面來源憑空消失。v3 把 `display.*` 從 Lua API 移除，目標顯示器改由 `:app` 在啟動
時決定並注入，引擎只掛上自己取影格的 surface、結束時拿掉，**顯示器本身不動**——Displays 頁
因此成為生命週期的唯一擁有者。

## Status

Accepted。取代 [ADR-0002](0002-lua-as-scripting-engine.md) 所描述 API 面貌中的 `display.*`
部分；Lua 作為腳本語言的決定本身不變。
````

| 原內容 | 去處 |
|---|---|
| ¶1–2 兩個擁有者 → 一個 | 留下 |
| `## 隨之而來的約束：實體螢幕沒有畫面辨識` | **刪除** — `docs/lua-api.md:48`、`:70`、`:188` 已完整記載 `screen.has_vision`（已驗證） |
| `## script.json 存的是尺寸，不是 displayId` | **待確認去處** — `lua-api.md` 是否已載明 `script.json` 的 `display` 欄位語意？未載明則移進該文件 → step (2) |

## 0012（78 → ~9）

````md
# Surface 尺寸由服務擁有，不由呼叫端回推

`getDisplaySize` 回的是**邏輯**尺寸（旋轉 90/270 時長寬互換），但 `AImageReader` 必須以
虛擬顯示**建立時**的 surface 尺寸開，於是呼叫端原本用「邏輯尺寸 + rotation」回推——那是
兩次獨立讀取，中間畫面轉了就會算出一組看起來合理、實際不自洽的答案，而影格緩衝區的長寬比
一錯就錯到腳本結束。改由知道的人記住它：`vdStore` 存
`ManagedDisplay(display, surfaceWidth, surfaceHeight)`，新增 `getDisplaySurfaceSize` 直接
回建立時的常數、完全不經過推導；服務沒建過的顯示器回 `[0, 0]` 讓呼叫端當場失敗，而不是
帶著可能錯的尺寸跑完整場。

## Status

Accepted。與 [ADR-0011](0011-scripts-do-not-own-displays.md) 一致——腳本不擁有顯示器，
也就不該是知道它幾何的人。
````

| 原內容 | 去處 |
|---|---|
| ¶1–3 競態與危害 | 壓進決定句 |
| `## 決定` 的四個 bullet | 壓進決定句 |
| ¶ `DisplayGeometry.surfaceSize` 留著 | **刪除** — 程式碼可見 |
| `## 沒有新測試，這是刻意的` | **刪除** — 關於「這次變更」的後設評論，不是決定 |
| `## 名字與旋轉不記在這裡` · 名字 | **刪除** — 平台已是擁有者，無人會想存 |
| `## 名字與旋轉不記在這裡` · 旋轉是活狀態 | **併入 CONTEXT.md** — 該處已陳述 surface/邏輯空間的擁有權 → step (2) |
| `## rotation 讀兩次是對的，不要合併` | **移進程式碼註解**（`ScriptEngine.kt:112`、`DisplayRotationTracker.kt:31`）→ step (2) |
| `## 已知的同類問題（另案處理）` | **刪除 — 已過期**。commit `4f93246`（2026-09-14）已修：`matchesSize` 現在直接比對 `getDisplaySurfaceSize`（已驗證 `ScriptSession.kt:158-161`） |

> ⚠️ **本檔最高風險項**。`## rotation 讀兩次是對的，不要合併` 自己記載著這份知識**已經遺失
> 過一次**（2026-09 的架構檢視把它當成可合併的重複回報過）。它必須先落地到那兩個程式碼註解，
> **才能**從 ADR 刪除。step (2) 未完成前不要執行 0012 的壓縮。

## 0013（72 → ~13）

````md
# 模板圖是邏輯空間的產物

`docs/lua-api.md` 向腳本作者承諾 surface 空間不存在。座標做到了，模板圖沒有：
`matchTemplate` 跑在未旋轉的影格上，而 `TM_CCOEFF_NORMED` 不是旋轉不變的，所以直立時截的
模板在橫向比不中，作者被迫為每個方向各備一張、且方向還是 surface 空間的。我們把承諾補成
真話——**模板圖是邏輯空間的產物，腳本作者提供的就是他在畫面上看到的方向**，引擎負責轉到
影格方向再比對。這是對外契約而非實作細節，無條件，不提供 `rotate = false` 之類的逃生門。

## Considered Options

- **轉影格而非轉模板**：數學上等價，命中位置一一對應。排除純粹是成本——模板一兩百像素且
  `templateFor` 有跨呼叫快取，影格是每張都新的 3.5 MB、快取不了。哪天成本反轉，換過去
  **不違反這份 ADR**：契約沒變，只是換了實作。
- **在 `GlesDistributor` 就轉正**：排除。它是扇出點，而鏡像已經自己在反旋轉，先轉會讓鏡像
  轉兩次；輸出尺寸還得隨旋轉改變，與 [ADR-0012](0012-surface-size-is-owned-not-derived.md)
  「surface 尺寸是被擁有的常數」衝突。

## Status

Accepted。
````

| 原內容 | 去處 |
|---|---|
| `## 脈絡` | 壓進決定句 |
| `## 脈絡` 尾「這個洞沒被發現因為沒有測試覆蓋」 | **刪除** — 開發過程敘事 |
| `## 決定` | 留下 |
| `## 目前的實作機制（不凍結）` · `templateFor`、快取鍵、方向推導 | **移進程式碼註解**（`VisionMatcher.cpp`，該處已有半段）→ step (2) |
| `## 目前的實作機制` · 成本對照表 | **`Considered Options`**，壓成一句 |
| `## 不在 GlesDistributor 轉` 三個理由 | **`Considered Options`**，壓成一句 |
| `## 後果` · 一張模板服務所有方向 | **刪除** — 即決定本身的重述 |
| `## 後果` · scale 不是尺度不變 | **刪除** — `docs/lua-api.md:38` 已載明（已驗證） |
| `## 後果` · 快取條目數 ×4 | **刪除** — 作者自評「可忽略」 |
| `## 後果` · `Tier1SpikeTest` 須保留對稱與不對稱兩圖樣 | **移進測試註解**（`Tier1SpikeTest.kt`）→ step (2)。少了它，旋轉方向寫反不會有測試失敗 |

> ⚠️ 「鏡像已經自己在反旋轉」這句的依據即將改變：[ADR-0014](adr/0014-mirror-is-pinned-to-panel-coordinates.md)
> 把反旋轉的依據從 `-v·90` 換成 `-d·90`。**論證本身仍然成立**（鏡像依然在反旋轉），所以上面
> 的改寫已刻意移除對 `Viewport.viewRotationDegrees` 的具體引用。

---

## 待你裁示

1. **0006 的標題**點名了兩個已不存在的模組。保留標題與檔名、由 `Status` 承擔？還是改標題但
   保留檔名（標題與 slug 會不一致）？
2. **0009 的標題前綴** `ADR-0009:` 是否拿掉以與其餘七篇一致？（純美觀）
3. **0007 的「Simple Script 錄製/編輯無替代方案」**：開 issue 承接後從 ADR 刪除？
4. **0012 的壓縮必須排在 step (2) 之後**——護欄要先落地到程式碼。其餘七篇可先行。

## 不在本文件範圍

- commit message：**永不修改**（已推送、且全部以編號引用，不會失效）
- 修復指向 ADR 內部的引用（`ScriptEngine.kt`、`DisplayRotationTracker.kt`、
  `LuaScriptLifecycleTest.kt`、`CONTEXT.md:62`）
- 上表所有標記「→ step (2)」的新註解與文件

以上全部是 step (2)。
