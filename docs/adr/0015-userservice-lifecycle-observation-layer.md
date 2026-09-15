# UserService 生命週期：加一層唯讀彙總，不合併機制/政策/持久化

`ShizukuManager`（連線機制）、`UserServiceAutoStarter`（啟動政策）、`AppSettings`（持久化開關）三者的分工是刻意設計，不合併；但「什麼時候會連上 UserService」原本要拼三個檔案才看得到全貌。我們加了 `UserServiceLifecycle`，唯讀彙總 `ShizukuManager.statusFlow` 與 `AppSettings.autoStartUserService`，比照 `ShizukuManager.statusFlow` 用 `stateIn(Eagerly)` 快取成單一熱資料源，讓不同訂閱者看到同一份值，不會各自重跑 `combine` 讀到不一致的組合。
