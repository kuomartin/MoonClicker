-- 最簡單的 Hello World 腳本
-- 推上裝置：
-- adb push "docs/examples/Hello MoonClicker" /sdcard/Android/data/com.xaxaxax.moonclicker/files/scripts/

log("Hello, MoonClicker World!")

-- 印出目前的螢幕解析度與旋轉角度
log("Screen Info:", screen.width .. "x" .. screen.height, "Rotation:", screen.rotation)

-- 將執行狀態記錄下來，方便在電腦端 (Script Workbench) 觀察
data.set("status", "Hello World 執行完畢")
