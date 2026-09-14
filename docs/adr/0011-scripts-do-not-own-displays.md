# 腳本不擁有顯示器的生命週期

目標顯示器由 `:app` 在啟動時決定並注入，引擎只把自己取影格用的 surface 掛上去、結束時拿掉
——**顯示器本身不動**。Lua API 因此沒有 `display.create`／`display.launch`／`display.get_all`：
Displays 頁是顯示器生命週期的唯一擁有者，兩個互不知情的擁有者只會互相收掉對方的顯示器。

## Status

Accepted。取代 [ADR-0002](0002-lua-as-scripting-engine.md) 所描述 API 面貌中的 `display.*`
部分；Lua 作為腳本語言的決定本身不變。
