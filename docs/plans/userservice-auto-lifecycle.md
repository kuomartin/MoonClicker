# UserService 自動啟停計畫（issue 127）

目標：一般使用流程中看不到 UserService 啟停的概念。設定頁只檢查 Shizuku 是否已授權；app 在前景或有東西要用 UserService 時自動啟動，app 退到背景後沒有腳本執行、沒有 managed VD、沒有鏡像、也沒有進行中的呼叫時自動停止。不提供 keep running 開關。

## A. 需求模型：租約

| # | 位置 | 內容 | 理由 |
|---|---|---|---|
| A1 | `shizuku/UserServiceLeases.kt`（新） | 計數器：`acquire()`／`release()`，`StateFlow<Int>` 對外 | 「現在有人要用」必須是一個可觀察的值，停止判斷才不會和進行中的呼叫競爭 |
| A2 | `UserServiceForegroundLease.kt`（新，`ProcessLifecycleOwner` 觀察者） | app 進入前景（`ON_START`）時取一個租約並在已授權時啟動服務；退到背景（`ON_STOP`）時釋放 | 使用者在 app 內操作時服務已經在跑，第一個動作不必等 bind（約 1 秒）；也讓頁面切換不會觸發停止 |
| A3 | `ShizukuManager.withService` | 進入時取租約、離開時釋放；未連線且已授權時呼叫 `startUserService()` 再等連線（取代現在只等不啟動） | 所有既有呼叫端（Displays、Fullscreen、ScriptSession、ScriptDetail）自動得到「需要時啟動」，不必逐一改 |
| A4 | `WorkbenchServer` 直接讀 `shizukuManager.service` 的兩處 | 改走 `withService`；H.264 鏡像串流在串流期間持有一個租約 | 直接讀 `.service` 在服務被停掉後拿到 null，Workbench 會靜默失效 |
| A5 | `ScriptSession` | 腳本執行期間持有一個租約（`start` 成功時取、`runState` 進入 terminal 時釋放） | 腳本執行中 `ScriptEngine` 持有 service 參考，但不經過 `withService` |

## B. 停止政策

| # | 位置 | 內容 | 理由 |
|---|---|---|---|
| B1 | `UserServiceAutoStopper.kt`（新，`@Singleton`，app scope） | 觀察「租約數＝0 且已連線」持續 [寬限期] 後，查一次 `getDisplayInfos()`：沒有任何 `isManaged` 或 `isMirrorActive` 的顯示器才呼叫 `stopUserService()` | 停止會銷毀所有 VD（`MoonClickerService.destroy()`），所以必須以服務端的實際狀態為準，不從 app 端推斷 |
| B2 | 寬限期 | 30 秒；期間任何 `acquire()` 取消倒數 | app 短暫切到背景又回來（看通知、切 app）時不重啟行程 |
| B3 | 查詢與停止之間的競爭 | 查詢本身也在 `withService` 內（持租約）；釋放後、呼叫 `stopUserService()` 前再確認租約數仍為 0，兩步在同一個 `Mutex` 內 | 避免查完到停之間剛好有人建立 VD |
| B4 | 觸發點 | 租約數歸零時啟動倒數；由通知列或 Shizuku 喚醒、沒有 UI 的 app 行程接上既有服務（Shizuku 的 user service 活過 app 被殺）後也評估一次 | 上一個 app 行程留下的服務若沒有 VD，應該被收掉 |
| B5 | 服務非預期斷線 | 不自動重啟；下一次 `withService` 自然會再啟動 | 與現在「斷線只探一次」一致，不在背景重試 |

## C. 移除的概念

| # | 位置 | 改動 | 理由 |
|---|---|---|---|
| C1 | `UserServiceAutoStarter`＋`MoonClickerActivity` 的呼叫 | 刪除 | 由 A2 的前景租約取代：不再受開關控制，且退到背景後會收掉 |
| C2 | `AppSettings.autoStartUserService` | 刪除（連同 SharedPreferences key） | 沒有開關了 |
| C3 | `UserServiceLifecycle`、`UserServiceLifecycleSnapshot` | 刪除；`SettingsViewModel` 直接讀 `ShizukuManager.statusFlow` | 它只彙總 `statusFlow` 與 `autoStartUserService`，後者刪除後只剩前者 |
| C4 | 設定頁 UserService 區塊 | 區塊改名「Shizuku」，只留授權列（未安裝／需要授權／已授權）；刪除自動啟動開關、啟動／停止／重新啟動按鈕 | 完成標準：一般使用流程中看不到 UserService |
| C5 | 開發人員選項 | 只放「停止 UserService」，保留確認對話框（會銷毀所有 VD）；不放「重新啟動」 | 停止後下一次 `withService` 會啟動新行程，等同重新啟動；debug build 同 versionCode 時 Shizuku 不重載服務，開發時仍需要這個手段 |
| C6 | `ShizukuStatusBar` | 只在 `NOT_AVAILABLE`／`NEED_PERMISSION` 顯示；`DISCONNECTED`／`CONNECTING`／`CONNECTED` 對使用者都是「就緒」 | 未連線是常態，不再是需要處理的狀態 |
| C7 | `ShizukuConnectionStatus` | 保留五個值（內部與開發人員選項仍用得到），UI 只看 `isAuthorized` | — |
| C8 | 字串 | 刪除 `settings_auto_start_user_service*`、`settings_user_service_start/stop*`；中英文 | — |

## D. Displays 頁的清單

| # | 內容 | 理由 |
|---|---|---|
| D1 | 不改：app 在前景時服務已由 A2 啟動，`DisplaysViewModel` 的「連上就重新整理」照舊 | 清單需要 `isManaged`／`isMirrorActive`，只有服務知道 |

## E. 文件

| # | 檔案 | 改動 |
|---|---|---|
| E1 | `docs/adr/0019-userservice-started-on-demand.md` | 按需啟動＋閒置停止、以服務端狀態判斷能否停止、不提供 keep running；取代 ADR-0015 |
| E2 | `docs/adr/0015-…` | 標註被 0019 取代 |
| E3 | `CONTEXT.md` | 若有 UserService 詞條，改寫生命週期描述 |

## F. 測試

| # | 位置 | 內容 |
|---|---|---|
| F1 | `UserServiceLeasesTest` | 巢狀取放、例外時仍釋放 |
| F2 | `UserServiceAutoStopperTest`（`TestDispatcher`＋假 service） | 租約歸零滿 30 秒且無 VD → 停；有 managed VD 或鏡像 → 不停；倒數中 `acquire` → 不停；查詢後、停止前有人 `acquire` → 不停 |
| F3 | `ShizukuManager.withService` | 未連線＋已授權 → 呼叫啟動；未授權 → 不啟動、逾時失敗 |
| F3b | `UserServiceForegroundLease` | `ON_START` 取租約並啟動、`ON_STOP` 釋放；未授權時只取租約不啟動，授權後補啟動 |
| F4 | 刪除 `UserServiceAutoStarterTest`、`UserServiceLifecycleTest`；更新 `DisplaysViewModelTest`、`ScriptSessionTest` 中依賴 auto-start 的部分 | — |
| F5 | 實機 | 開 app 即啟動服務；app 退到背景、無腳本、無 VD，30 秒後服務行程消失（`ps -A \| grep MoonClickerService`）；有 VD 時不消失；Workbench 串流期間不消失 |
