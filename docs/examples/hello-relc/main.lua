-- 端到端冒煙測試用的腳本：不需要任何模板圖片就能跑完。
-- 推上裝置：
--   adb push docs/examples/hello-relc /sdcard/Android/data/com.xaxaxax.relc/files/scripts/

log("hello from ReLC", screen.width .. "x" .. screen.height, "rotation", screen.rotation)
data.set("stage", "started")

if screen.has_vision then
    data.set("vision", "available")
    -- 沒有模板也想證明 vision 這條路是通的：故意找一張不存在的圖，
    -- 它會跑完整個等待迴圈然後逾時回傳 nil，而不是卡住。
    local missing = vision.wait({ image = "does-not-exist.png" }, 2000)
    data.set("missing_template_result", tostring(missing))
else
    data.set("vision", "unavailable (physical display)")
end

-- 點在螢幕正中央，然後滑一下：驗證輸入注入與「做完才往下走」的語意。
input.tap(screen.width // 2, screen.height // 2)
sleep(500)
input.swipe({ screen.width // 2, screen.height * 3 // 4,
              screen.width // 2, screen.height // 4 }, 400)

data.set("stage", "finished")
log("done")

function on_stop()
    log("on_stop ran")
end
