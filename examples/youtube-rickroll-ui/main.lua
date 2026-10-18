-- 在虛擬顯示器上開 YouTube，播放 Rick Astley〈Never Gonna Give You Up〉：
-- 搜尋、略過廣告、拖曳進度條快轉到一半，再讀出時間確認。影片會繼續播放。
--
-- 與 youtube-rickroll 做的事相同，但用 ui 元件樹取代 OCR：元件用 resource id 找，
-- 不受介面語言影響，也不用從畫面比例推算進度條的位置。
--
-- 前提：Android 11 以上；YouTube 已經手動開過一次（登入、首次啟動的對話框都處理掉了）。

local W, H = screen.width, screen.height

local function fail(reason)
    data.set("status", reason)
    device.notify("YouTube 範例", reason)
    error(reason)
end

-- 依序找 selectors，回傳第一個找到的節點與它的索引
local function find_first(selectors)
    for i, selector in ipairs(selectors) do
        local node = ui.find(selector)
        if node then return node, i end
    end
end

-- 1. 開 YouTube，打開搜尋 -----------------------------------------------------
-- YouTube 可能停在上次的任何一頁，所以不去找畫面上的搜尋按鈕，而是送搜尋鍵（KEYCODE_SEARCH），
-- 在哪一頁都會打開搜尋。冷啟動時按鍵可能在畫面出來前就送到而被忽略，所以等不到搜尋框就再按一次。
local KEYCODE_SEARCH = 84
app.launch("com.google.android.youtube")
local field
for _ = 1, 5 do
    input.key(KEYCODE_SEARCH)
    field = ui.wait({ id = "search_edit_text", focused = true }, 3000)
    if field then break end
end
if not field then fail("打不開搜尋") end

-- 2. 輸入關鍵字 ---------------------------------------------------------------
-- 搜尋框已經取得焦點；結尾的 \n 等於按下 Enter 送出搜尋
log("輸入關鍵字")
input.text("rick astley never gonna give you up\n")

-- 3. 點進影片 -----------------------------------------------------------------
-- 搜尋結果沒有 resource id，但每一則的 contentDescription 以影片標題開頭，標題不隨介面語言翻譯。
-- 比對「歌手 - 歌名」的格式，才會點到官方 MV 而不是現場版或翻唱；開頭的贊助廣告不會符合。
-- 清單只有畫面上的項目，找不到就往下捲。
local VIDEO = { desc_contains = "Rick Astley - Never Gonna Give You Up" }
local video
for attempt = 1, 4 do
    video = ui.wait(VIDEO, attempt == 1 and 10000 or 3000, 500)
    if video then break end
    log("往下捲")
    input.swipe({ W // 2, H * 3 // 4, W // 2, H // 4 }, 400)
    sleep(800)
end
if not video then fail("搜尋結果裡找不到影片") end
log("點進影片", video.desc)
input.tap(video.cx, video.cy)

-- 播放器出現才算進到影片頁；之後點播放器叫出控制列都以它的外框為準
local player = ui.wait({ id = "watch_player" }, 10000)
if not player then fail("影片頁沒有出現") end

-- 4. 處理廣告 -----------------------------------------------------------------
-- 廣告期間播放器上會有略過按鈕或廣告倒數，用它們判斷而不是看影片標題：
-- 廣告也可能在正片播了幾秒之後才插進來，那時標題早就在了。可能連續好幾則，有的不能略過。
local SKIP_BUTTON = { id = "skip_ad_button", clickable = true }
local AD_SIGNS = {
    SKIP_BUTTON,
    { id = "skip_ad_button" },  -- 倒數中、還不能點
    { id = "ad_progress_text" },
}

-- 等到連續 quiet 秒都沒有廣告；期間出現略過按鈕就點。
local function skip_ads(quiet)
    local clear = 0
    for _ = 1, 180 do
        local node, idx = find_first(AD_SIGNS)
        if idx == 1 then
            log("略過廣告")
            input.tap(node.cx, node.cy)
            clear = 0
        elseif node then
            clear = 0
        else
            clear = clear + 1
            if clear >= quiet then return end
        end
        sleep(1000)
    end
    fail("廣告播不完")
end

-- 5. 讀出目前時間 -------------------------------------------------------------
-- 時間與進度條在控制列上，控制列隱藏時不在元件樹裡：點播放器下半部叫出來（避開正中央的暫停鍵）。
-- 總長太短代表讀到的是廣告的時間。
local function seconds(clock)
    local m, s = clock:match("(%d+):(%d+)")
    return m and tonumber(m) * 60 + tonumber(s)
end

local function read_time()
    input.tap(player.cx, player.y + player.h * 3 // 4)
    local now = ui.wait({ id = "time_bar_current_time" }, 2000)
    local total = ui.find({ id = "time_bar_total_time" })
    local bar = ui.find({ id = "time_bar" })
    if not (now and now.text and total and total.text and bar) then return end
    local now_s, total_s = seconds(now.text), seconds(total.text)
    if now_s and total_s and total_s >= 60 then return bar, now_s, total_s end
end

-- 6. 拖曳進度條到一半 ---------------------------------------------------------
-- YouTube 的進度條是相對拖曳：手指移動的距離決定前進多少，跟按下的位置無關。
-- 所以從目前的位置按下，往目標的方向移「差距的比例 × 進度條寬度」；YouTube 可能從上次的位置接著播，目標也可能在左邊。
local function drag(bar, now, target, total)
    local from_x = bar.x + bar.w * now // total
    local to_x = bar.x + bar.w * target // total
    input.down(1, from_x, bar.cy)
    for step = 1, 10 do
        sleep(30)
        input.move(1, from_x + (to_x - from_x) * step // 10, bar.cy)
    end
    input.up(1)
end

function on_stop()
    input.up(1)
end

-- 7. 確認快轉成功 -------------------------------------------------------------
-- 剛拖曳完控制列還開著，直接讀目前時間
local function read_after()
    sleep(300)
    local now = ui.find({ id = "time_bar_current_time" })
    local text = now and now.text and now.text:match("%d+:%d+")
    return text and seconds(text), text
end

-- 4–7 整段可能被插進來的廣告打斷，打斷就等廣告過去再從頭來。
local result
for attempt = 1, 3 do
    skip_ads(3)
    local bar, now, total = read_time()
    if bar then
        log(string.format("目前 %d 秒，全長 %d 秒", now, total))
        local target = total // 2
        drag(bar, now, target, total)
        local after, now_text = read_after()
        if after then
            log(string.format("快轉後 %d 秒，目標 %d 秒", after, target))
            -- 影片一直在播、拖曳也有誤差，差 10 秒內都算成功
            if math.abs(after - target) <= 10 then
                result = now_text
                break
            end
        end
    end
    log("被廣告打斷或讀不到時間，重試", attempt)
end
if not result then fail("快轉沒有成功") end
data.set("position", result)
data.set("status", "never gonna give you up")
