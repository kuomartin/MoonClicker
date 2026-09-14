# step (2)：引用修復與新增註解

**這是一份過渡文件。** 與 [markdown(1)](adr-format-cleanup-plan.md) 一起，執行完就刪除。

前提：(1) 已定案——0006 移除，0007–0013 壓縮至結論，編號不重排。

## 盤點結果：護欄幾乎都已經在程式碼裡

(1) 列了 11 段「應移進程式碼」的護欄。逐一比對原始碼後，**9 段已經在了**，而且多半寫得比
ADR 更完整；2 段已經過期。

| ADR 段落 | (1) 的計畫 | 實際 |
|---|---|---|
| 0008 ¶3 同名套件未受影響 | 移進註解 | **已過期**——`script/simple/*` 已於 `9dc6e82`（2026-09-12）刪除，比 ADR-0008（`777ed88`，2026-09-10）晚兩天 |
| 0009 為什麼不放 `:hidden-api` | 移進 build 腳本 | **已在** `hidden-api-contract/build.gradle.kts:11-13` |
| 0009 為什麼不放 `:engine` | 移進 build 腳本 | **已在** 同檔 `:14-16` |
| 0009 `androidTestCompileOnly` 機制 | 移進 build 腳本 | **已在** 同檔 `:113-116` |
| 0009 JLS 13.1、故表用 Java | 移進註解 | **已在** `VirtualDisplayFlagTable.java:14-22` |
| 0009 新增 stub 要同步加一筆 | 移進註解 | **已在** `HiddenApiContracts.kt:11` |
| 0010 Stopped 而非 Error | 移進測試註解 | **已在** `LuaScriptLifecycleTest.kt:47-50` |
| 0012 rotation 讀兩次 | 移進兩處註解 | **已在** `ScriptEngine.kt:112-113` 與 `DisplayRotationTracker.kt:31-36` |
| 0013 `templateFor` 機制與成本 | 移進註解 | **已在** `VisionMatcher.cpp:96-102` |
| 0013 對稱/不對稱圖樣 | 移進測試註解 | **已在** `Tier1SpikeTest.kt:292`、`:357-359` |
| 0011 `script.json` 存尺寸不存 id | 移進註解 | **部分**——`ScriptTarget.kt:19` 有「沿用同尺寸」，缺「為何不是 id」 |
| 0007 行程存活 | 移進註解 | **缺** |

結論：ADR 0009–0013 的膨脹段落絕大多數是**複製程式碼註解**，不是知識的唯一所在。壓縮它們
不會遺失任何東西。

### 兩項對 (1) 的修正

1. **0012 不必排在 step (2) 之後。** 我先前把它標成「最高風險項、必須先落地才能刪」，那是
   錯的——兩段交接的完整推理（含「順序不能對調」）已經在那兩個檔案裡。**八篇可以一起做。**
2. **0008 ¶3 改為「刪除（已過期）」**，不再是「移進註解」。

---

## A. 新增註解（2 處）

### A1. `ScriptTarget.kt` — 為何存尺寸不是 displayId

`docs/lua-api.md:222-232` 已載明 `script.json` 的 `display` 綱要，但沒有理由。現有註解只說
「若已有同尺寸的就沿用」，沒說為什麼不直接存 id。

```kotlin
    /**
     * 開一個新的虛擬顯示（若已有同尺寸的就沿用）。
     * 腳本 `script.json` 裡的 `display` 就是解析成這個。
     *
     * 存的是尺寸而不是 displayId：id 每次重建都會變，存下來的必然過期。
     */
    data class NewVirtual(val config: DisplayConfig) : ScriptTarget
```

### A2. `ScriptStatusNotifier.kt` — 行程存活

ADR-0007 唯一不在程式碼裡、且描述**現在出貨行為**的一段。

```kotlin
/**
 * 執行中的腳本唯一的系統層可見處（Overlay UI 已移除，見 ADR-0007）。
 *
 * 一次只跑一份腳本，所以這裡不需要上一代那個彙總多份腳本的 summary——常駐通知就是
 * 那一份腳本本身，動作只有「停止」。
 *
 * 通知**不會**讓行程活著。被移除的 AccessibilityService 原本順帶有這個效果，而
 * `setOngoing(true)` 沒有，所以長時間執行的腳本可能在使用者切到別的 app 之後被系統殺掉。
 * 要真正錨住執行，需要的是前景服務，那是另一個還沒做的決定。
 */
```

## B. 指向被刪 ADR-0006 的引用（4 處）

| 位置 | 現況 | 改為 |
|---|---|---|
| `CONTEXT.md:58` | `... directly. See [ADR-0006](...).` | 刪句末連結，事實本身留著 |
| `CONTEXT.md:62` | `... per-virtual-display state — see [ADR-0006](...)'s scope note.` | `... per-virtual-display state: that would mean wiring `RelcV2Service`'s display bookkeeping — a separate source of truth — into the same repository, deliberately left unstarted rather than half-built.` |
| `CONTEXT.md:66` | `... directly. See [ADR-0006](...).` | 刪句末連結 |
| `docs/adr/0002` Status | `— see [ADR-0006](...).` | `— see the Engine Module entry in `CONTEXT.md`.` |

`docs/adr/0007` ¶3、`0008` ¶2、`0009` Status、`0012` Status 的 ADR-0006 連結隨各自的壓縮
一併消失，不需要單獨處理。

## C. 指向 ADR 內部段落的引用（5 處）

規則：引用 ADR 只引編號與決定；需要一段理由就就地寫清楚。以下五處的理由**都已經寫在現場**，
所以只要把指路的那半句拿掉。

| 位置 | 現況 | 改為 |
|---|---|---|
| `ScriptEngine.kt:112` | `（ADR-0012 兩段交接的第一段）` | `（兩段交接的第一段；第二段在 DisplayRotationTracker.start）` |
| `DisplayRotationTracker.kt:31` | `它是兩段交接的第二段（見 ADR-0012）。` | `它是兩段交接的第二段。` |
| `LuaScriptLifecycleTest.kt:50` | `分辨它們是 ADR-0010 的承諾。` | `分辨它們是引擎的對外承諾。` |
| `Tier1SpikeTest.kt:216` | `（ADR-0012 的 Surface 空間／邏輯空間）` | `（見 CONTEXT.md 的「Surface 空間 / 邏輯空間」）` |
| `LuaScreenApiTest.kt:16` | `... ADR-0012）：影格是 surface 空間 ...` | 同上，改指 `CONTEXT.md` |

> `Tier1SpikeTest` 與 `LuaScreenApiTest` 引的是**術語**而非決定，術語的擁有者是 `CONTEXT.md`
> 的詞彙表，不是 ADR。

## D. 維持原樣的引用

以下引用的是**編號與決定本身**，符合新規則，不動：

- `ScriptSession.kt:192` → ADR-0012（surface 尺寸的擁有權）
- `ScriptStatusNotifier.kt:21` → ADR-0007（Overlay UI 已移除）
- `VisionMatcher.cpp:102` → ADR-0013（模板是邏輯空間的契約）
- `LuaScriptLifecycleTest.kt:14` → ADR-0010（線性腳本）
- `CONTEXT.md` 對 0001/0002/0003/0004/0010/0011/0012/0013 的引用

## E. commit message

**不修改。** 19 個提到 ADR 的 commit 全部以編號引用，而編號不重排，所以沒有一個會失效。

唯一指向被刪 ADR 的是
`603d1ec feat: Wire ScriptCompiler output into NativeLuaScriptRunner (ADR-0006 follow-up)`。
接住它的是保留下來的 **ADR-0008**——0008 本文記著那個功能在刪除當下仍是活的，讀到該 commit
的人因此不會誤以為它還在。

---

## 執行順序

(1) 與 (2) 的改動互不相依（0012 的前置條件已解除），可以一次做完：

1. 刪除 `docs/adr/0006-module-split-and-engine-facade.md`
2. 改寫 `docs/adr/0007`–`0013` 為 (1) 的提案文字
3. 套用 A、B、C 三組編輯
4. 刪除 `docs/adr-format-cleanup-plan.md` 與本檔
