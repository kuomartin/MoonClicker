# 腳本不擁有顯示器的生命週期

v2 的腳本用 `display.create(w, h)` 自己開虛擬顯示，而 `RelcEngine::stop()` 在收尾時直接
`destroyVirtualDisplay()`。這跟 Displays 頁自己管理顯示器（建立、查看、關閉）是兩個互相
不知道對方存在的擁有者：腳本跑完會把使用者在 Displays 頁建的顯示器一起收掉，而使用者在
Displays 頁關掉顯示器則會讓腳本的畫面來源憑空消失。

v3 把 `display.create` / `display.launch` / `display.get_all` 從 Lua API 全部移除。
目標顯示器由 `:app` 在啟動時決定並注入（Scripts 頁的「在指定顯示器執行」，或 `script.json`
的 `display` 欄位），引擎只把自己取影格用的 surface 掛上去，結束時拿掉——**顯示器本身不動**。
Displays 頁因此成為顯示器生命週期的唯一擁有者，`FullscreenDisplayActivity` 也收斂成純粹的
鏡像與模板裁切工具，不再有執行腳本的按鈕（issue #5）。

## 隨之而來的約束：實體螢幕沒有畫面辨識

`RelcV2Service.addVirtualDisplaySurface` 只對服務自己建立的虛擬顯示有效（查不到 distributor
就失敗），所以 display 0 拿不到影格。這個限制在 v2 被 `display.create` 的存在掩蓋著——腳本
總是跑在自己開的虛擬顯示上。把目標交給 App 選之後它就浮上來了。

處理方式是把它講明白，而不是假裝沒有：目標是實體螢幕時 `screen.has_vision` 為 `false`，
呼叫 `vision.*` 會拋出說明原因的 Lua 錯誤，顯示器選擇器上的「本機螢幕」也標註了
「不支援畫面辨識」。靜默地永遠比對不到會是更糟的失敗方式。

## `script.json` 存的是尺寸，不是 displayId

虛擬顯示的 id 每次重建都會變，存下來的必然過期。所以 `script.json` 的 `display` 存的是
「要一個長這樣的顯示器」（寬、高、密度），執行時先找現有同尺寸的沿用，沒有才建新的。

## Status

Accepted。取代 [ADR-0002](0002-lua-as-scripting-engine.md) 所描述 API 面貌中的 `display.*` 部分；
Lua 作為腳本語言的決定本身不變。
