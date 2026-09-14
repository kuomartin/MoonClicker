# 腳本是線性程式，不是 tick 迴圈

`main.lua` 從上到下執行，跑完就結束；`sleep`、`vision.wait`、`input.*` 直接在腳本自己的執行緒上阻塞。唯一的回呼是選填且僅呼叫一次的 `on_stop()`，它不構成第二套執行模型——一套就夠了，而「一步接一步」的流程是自動化的絕大多數，在固定頻率的 tick 迴圈裡反而得自己維護狀態機。

## Consequences

持續監控型的自動化要自己寫 `while true` 配 `vision.wait_any`。這被判斷為可接受：那個迴圈是顯式的、看得懂的。

## Status

Accepted.
