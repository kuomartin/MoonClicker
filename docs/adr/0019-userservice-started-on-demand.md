---
status: accepted
---

# UserService 按需啟動、閒置停止，不提供常駐開關

UserService（`MoonClickerService`）由 App 自動啟停，使用者只需要授權 Shizuku。「有人要用」以租約表達（`UserServiceLeases`）：App 在前景、`withService` 呼叫期間、腳本執行期間、Workbench 串流期間各持有一個；有租約且已授權時啟動服務。租約歸零滿 30 秒後，先向服務查詢顯示器，沒有 managed VD 也沒有鏡像才停止。理由：停止會銷毀所有 VD，能不能停只能以服務端的實際狀態為準，不能從 App 端推斷；而把啟停交給使用者，就要先讓他理解什麼是 UserService，這正是新使用者上手的障礙。

## Considered Options

- **保留「自動啟動」開關與手動啟停按鈕**（取代 ADR-0015 的做法）：想掌握特權行程何時存在的人可以自己控制，但一般使用者必須理解 UserService 才能排除「服務沒連上」的狀況。開發時需要重載服務的情境改由開發人員選項的「停止」處理：停止後下一次用到時會啟動新行程。
- **Workbench 開著就一直保持服務**：實作較簡單，但開著 Workbench 的使用者服務永遠不會停。改成 Workbench 的請求與串流按需持有租約。
- **服務斷線後自動重啟**：服務在啟動時崩潰會變成迴圈。只在「租約從無到有」與「授權到位」時啟動，斷線後等下一次 `withService` 按需啟動。

## Consequences

- 查詢顯示器與停止之間可能有人取租約建立 VD；`UserServiceLeases.runIfIdleSince` 以「查詢開始後沒有人取過租約」為停止條件，並與 `acquire` 共用同一把鎖。
- App 只要在前景，即使只是在編輯腳本，服務也會在跑；換來的是第一個動作不必等 bind。
