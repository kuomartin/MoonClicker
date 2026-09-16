-- 範例腳本：啟動遊戲並比對畫面按鈕
-- 推上裝置：
-- adb push "docs/examples/Alto's Adventure" /sdcard/Android/data/com.xaxaxax.relc/files/scripts/

log("hello from ReLC", screen.width .. "x" .. screen.height, "rotation", screen.rotation)
data.set("stage", "started")

local appSuccess = app.launch("com.noodlecake.altosadventure")

log("app_success", appSuccess)

if appSuccess then
    data.set("app_success", tostring(appSuccess))
end

local delta_px = 5

if screen.has_vision then
    data.set("vision", "available")
    local stoneReq = {
        image = "stone.png",
        roi = {
            x = 829 - delta_px,
            y = 809 - delta_px,
            w = 124 + delta_px * 2,
            h = 164 + delta_px * 2
        }
    }

    local stone = vision.wait(stoneReq, 20000)
    data.set("stone_found", tostring(stone ~= nil))
    data.set("stone_result", tostring(stone))

    log("stone_found", tostring(stone ~= nil))
    log("stone_result", tostring(stone))

    local pictureModReq = {
        image = "pictureMod.png",
        roi = {
            x = 39 - delta_px,
            y = 608 - delta_px,
            w = 317 + delta_px * 2,
            h = 98 + delta_px * 2
        }
    }

    local pictureMod = vision.wait(pictureModReq, 20000)
    data.set("pictureMod_found", tostring(pictureMod ~= nil))
    data.set("pictureMod_result", tostring(pictureMod))

    log("pictureMod_found", tostring(pictureMod ~= nil))
    log("pictureMod_result", tostring(pictureMod))

    if pictureMod ~= nil then
        log("pictureMod_center", pictureMod.cx .. ", " .. pictureMod.cy)
        input.tap(pictureMod.cx, pictureMod.cy)
        sleep(2000)
    end
else
    data.set("vision", "unavailable (screen.has_vision is false)")
end

data.set("stage", "finished")
log("done")

function on_stop()
    log("on_stop ran")
end
