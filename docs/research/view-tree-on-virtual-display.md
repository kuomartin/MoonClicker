# VD 上的 view tree 取得方式（issue #196）

研究日期 2026-10-13。原始碼出處為 AOSP `frameworks/base` 的 `android-15.0.0_r1`，下文 `F/<path>#N` 指 `https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/<path>#LN`。實機為 Pixel 7a（API 37），量測程式是 `engine/src/androidTest/.../script/ViewTreeProbeTest.kt`。

---

## 結論

1. **可行：shell 身分的 `UiAutomation` 能取得 MoonClicker VD 上的視窗與完整節點樹。** 在 Shizuku UserService（shell，uid 2000）裡自建 `UiAutomation(looper, UiAutomationConnection())` 並 `connect()`，設 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`，以 `getWindowsOnAllDisplays()[displayId]` 取視窗、`window.root` 往下走節點。`uiautomator` 指令（`UiAutomationShellWrapper`）也是這樣建立的。
2. **API 30 起支援。** `getWindowsOnAllDisplays()` 是 API 30；API 27–29 的 `getWindows()`／`getRootInActiveWindow()` 只看預設顯示器，VD 上拿不到。0.5 的 view tree 功能在 API 30 以下回報不支援即可。
3. **成本：第一次走完整棵樹約 230 ms（Settings 首頁 154 節點），之後約 2 ms。** 後者是 `AccessibilityCache` 命中，畫面變動後 cache 會被 accessibility event 清掉，實際成本介於兩者之間，接近第一次（推論，未量測畫面持續變動的情境）。Lua 端應設計成「需要時取一次」，不要每個 tick 輪詢。
4. **限制：同一時間只能有一個 UiAutomation，而且預設會壓制使用者的無障礙服務。** 已有 instrumentation 測試或 `uiautomator` 在跑時，註冊會失敗；註冊時沒帶 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，TalkBack 等服務會被停用。production 應帶這個旗標，並只在有腳本或 MCP 用到 view tree 時才註冊、用完即釋放。
5. **VirtualDevice 建立的 VD（#124）預期同樣有效，但未實測。** 無障礙系統只排除 overlay 顯示器與非 SystemUI 擁有的 private VD；MoonClicker 的 VD 帶 `VIRTUAL_DISPLAY_FLAG_PUBLIC`，改走 VirtualDevice 時保留這個旗標即可。#124 完成後在新路徑上重跑 `ViewTreeProbeTest` 確認。
6. **不需要替代方案。** 呼叫 `uiautomator dump` 底層是同一個 UiAutomation，只多了 XML 序列化與 process 啟動成本；AccessibilityService 已在 ADR-0007 移除，不考慮回頭。

---

## 一、權限與註冊

| 事實 | 出處 |
|---|---|
| `registerUiTestAutomationService` 要求 `RETRIEVE_WINDOW_CONTENT`，shell 具備 | `F/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#1473` |
| 已有一個 UiAutomation 時丟 `IllegalStateException("... already registered!")` | `F/services/accessibility/java/com/android/server/accessibility/UiAutomationManager.java#108` |
| 沒帶 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 時壓制其他無障礙服務 | `UiAutomationManager.java#172` |
| `UiAutomation(Looper, IUiAutomationConnection)` 是 hidden API，需走 `:hidden-api`（ADR-0009） | `F/core/java/android/app/UiAutomation.java#304` |

instrumentation 的 `uiAutomation` 與上述自建的實例走同一個 `registerUiTestAutomationService`、同一個 shell 權限，所以 `ViewTreeProbeTest` 的結果可以代表 production 的做法。

## 二、哪些顯示器看得到

`getWindowsOnAllDisplays()`（`UiAutomation.java#905`）回傳無障礙系統追蹤中的每個顯示器。顯示器是否被追蹤由 `AccessibilityDisplayListener.isValidDisplay` 決定（`AccessibilityManagerService.java#5477`）：

- `TYPE_OVERLAY` 排除。
- `TYPE_VIRTUAL` 且帶 `FLAG_PRIVATE`、擁有者不是 SystemUI 時排除。
- 其餘都追蹤，包含 MoonClicker 的 public VD。

## 三、實機結果

Pixel 7a，API 37，Tier1Env 建立的 VD（720×1280）。

| 情境 | 視窗 | 節點數 | 第一次 | 中位數（10 次） |
|---|---|---|---|---|
| Settings 首頁 | `{type=APPLICATION title=設定 pkg=com.android.settings}` | 154 | 227.7 ms | 2.2 ms |
| 測試用 puppet（單一 Compose View） | `{type=APPLICATION title=MoonClicker Puppet}` | 4 | 4.1 ms | 0.9 ms |

觀察：

- 節點帶 `className`、`text`、`viewIdResourceName`、`boundsInScreen`，座標是 VD 自己的座標系，可直接對應 `touch` 與 vision 的邏輯座標。
- `RecyclerView` 只有畫面內的項目（Settings 的樹只到 y=919），畫面外的元素要捲動後重取。
- Compose 畫面若沒有 semantics，只看得到一個 `android.view.View`（puppet 的情況）；遊戲類 SurfaceView 畫面同理，view tree 對它們沒有幫助，仍需 vision。
