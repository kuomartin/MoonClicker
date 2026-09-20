-- 範例腳本：啟動遊戲並比對畫面按鈕

log("hello from MoonClicker", screen.width .. "x" .. screen.height, "rotation", screen.rotation)
data.set("stage", "started")

local appSuccess = app.launch("com.noodlecake.altosadventure")

log("app_success", appSuccess)

if appSuccess then
    data.set("app_success", tostring(appSuccess))
end

---add padding
---@param image string
---@param roi moonclicker.VisionRoiRect
---@param delta_px integer
---@return moonclicker.VisionRequest
local function template(image, roi, delta_px)
    local r = {
        x = roi.x - delta_px,
        y = roi.y - delta_px,
        w = roi.w + delta_px * 2,
        h = roi.h + delta_px * 2
    }
    return {
        image = image,
        -- roi = r
    }
end

log("rotation", screen.rotation, "w", screen.width, "h", screen.height)
local stone_roi = { x = 829, y = 809, w = 124, h = 164 }
local continue_roi = { x = 500, y = 913, w = 129, h = 579 }
local retry_roi = { x = 16, y = 2047, w = 111, h = 310 }

local reqs = {
    template("continue.png", continue_roi, 5),
    template("retry.png", retry_roi, 5)
}

local stone_req = template("stone.png", stone_roi, 5)

data.set("vision", tostring(screen.has_vision))
local hit_stone = vision.wait(stone_req, 20000)

if hit_stone then
    input.tap(hit_stone.cx, hit_stone.cy)
    data.set("status", "tapped_stone")
else
    log("stone not found.")
end

while screen.has_vision do
    log("rotation", screen.rotation, "w", screen.width, "h", screen.height)
    local idx,hit = vision.wait_any(reqs, 5000)

    if hit then
        log("matched", idx, "at", hit.cx, hit.cy)
        sleep(1000)
        input.tap(hit.cx, hit.cy)
        data.set("status", "tapped_" .. idx)
    else
        log("timeout, nothing matched")
        data.set("status", "timeout")
        input.tap(screen.width // 2, screen.height // 2, 50)
    end
end

data.set("stage", "finished")
log("done")

function on_stop()
    log("on_stop ran")
end
