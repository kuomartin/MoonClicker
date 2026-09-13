# 腳本是線性程式，不是 tick 迴圈

v2 的引擎同時提供兩套執行模型：一個固定頻率的 `on_tick(matches, tick_num)` 迴圈（預設約 15 FPS，
由 `config.fps` 調整），以及一條會 yield 的 Lua coroutine 主線（`wait()`、`match.wait()`）。
腳本作者得同時理解兩者，而「一步接一步」的自動化流程——自動化腳本的絕大多數——在 tick 迴圈裡
要靠自己維護狀態機才寫得出來。

v3 只留線性模型：`main.lua` 從上到下執行，跑完就結束。`sleep`、`vision.wait`、`input.*` 直接在
腳本的執行緒上阻塞。`on_tick`、`on_start`、`config` 全域表全部移除；唯一保留的回呼是選填的
`on_stop()`，它只會被呼叫一次，不構成第二套模型。

## 實作上的簡化

線性語意讓 coroutine 本身也變得沒有必要。腳本跑在自己的執行緒上，沒有別的 Lua 工作要交錯，
所以 `lua_newthread` / `lua_resume` / `lua_yield` 整組拿掉，改成在專屬執行緒上一個
`lua_pcall`。阻塞呼叫用 condition variable 實作，停止時 broadcast 打斷——這也順手修掉了
v2 的一個回報錯誤：`lua_stop_hook` 用 `luaL_error` 中止腳本，於是使用者主動停止會被回報成
`Error("Script terminated by user")` 而不是 `Stopped`。現在錯誤處理會先看停止旗標，
主動停止一律是 `Stopped`。

比對也跟著改成隨需執行。v2 的影格回呼對所有啟用的模板無條件跑 `cv::matchTemplate`，
不管腳本當下是不是在等任何東西；v3 的影格回呼只存下影格並喚醒等待者，OpenCV 只在
`vision.find` / `vision.wait` 呼叫期間執行。模板也因此不再需要全域的 `config.templates`
/ `match.templates` 兩套來源——在呼叫點指定就好。

## 代價

持續監控型的自動化（「一直看著畫面，出現什麼就處理什麼」）現在要自己寫 `while true do ... end`
配 `vision.wait_any`。這被判斷為可接受：那個迴圈是顯式的、看得懂的，而 v2 的 tick 迴圈
把它藏成了框架行為。

## Status

Accepted.
