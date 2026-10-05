-- 在虛擬顯示器上開 YouTube，播放 Rick Astley〈Never Gonna Give You Up〉：
-- 搜尋、略過廣告、拖曳進度條快轉到一半，再讀出時間確認。影片會繼續播放。
--
-- 前提：YouTube 已經手動開過一次（登入、首次啟動的對話框都處理掉了）；介面為中文或英文。
-- 座標一律從比對結果或 screen.width/height 推算，不寫死。

local W, H = screen.width, screen.height
-- 直式畫面的播放器貼齊頂端、比例 16:9，進度條在它的底邊
local PLAYER_H = W * 9 // 16
local PLAYER = { 0, 0, W, PLAYER_H }

local function fail(reason)
    data.set("status", reason)
    device.notify("YouTube 範例", reason)
    error(reason)
end

-- 1. 開 YouTube，打開搜尋 -----------------------------------------------------
-- YouTube 可能停在上次的任何一頁，所以不去找畫面上的搜尋按鈕，而是送搜尋鍵（KEYCODE_SEARCH），
-- 在哪一頁都會打開搜尋。冷啟動時按鍵可能在畫面出來前就送到而被忽略，所以等不到搜尋框就再按一次。
-- 搜尋框只看頂端左半部：右半部可能被上次留下的縮小播放器蓋住。
local KEYCODE_SEARCH = 84
local search_bar = { 0, 0, W // 2, H // 8 }
app.launch("com.google.android.youtube")
local field
for _ = 1, 5 do
    input.key(KEYCODE_SEARCH)
    local _, hit = vision.wait_any({
        { text = "搜尋", roi = search_bar },
        { text = "Search", roi = search_bar },
    }, 3000)
    field = hit
    if field then break end
end
if not field then fail("打不開搜尋") end

-- 2. 輸入關鍵字 ---------------------------------------------------------------
-- 搜尋框已經取得焦點；結尾的 \n 等於按下 Enter 送出搜尋
log("輸入關鍵字")
input.text("rick astley never gonna give you up\n")

-- 3. 點進影片 -----------------------------------------------------------------
-- 結果頁最上面常是贊助廣告，有時候大到把影片擠出畫面，所以找影片標題而不是點第一個結果，
-- 找不到就往下捲。標題比對「歌手 - 歌名」的格式，才會點到官方 MV 而不是現場版或翻唱。
-- roi 避開頂端的搜尋框；step_ms 讓整張辨識不要連續跑。
local below_search = { 0, H // 10, W, H - H // 10 }
local video
for attempt = 1, 4 do
    video = vision.wait({ text = "Rick Astley - Never Gonna", roi = below_search }, attempt == 1 and 10000 or 4000, 1000)
    if video then break end
    log("往下捲")
    input.swipe({ W // 2, H * 3 // 4, W // 2, H // 4 }, 400)
    sleep(800)
end
if not video then fail("搜尋結果裡找不到影片") end
log("點進影片", video.text)
input.tap(video.cx, video.cy)

-- 4. 處理廣告 -----------------------------------------------------------------
-- 播放器下方出現影片標題就代表正片開始了；廣告期間那裡是廣告資訊。
-- 「略過」按鈕要等廣告播幾秒才出現，可能連續兩則；不能略過的廣告就等它播完。
local below_player = { 0, PLAYER_H, W, H // 8 }
while true do
    local idx, hit = vision.wait_any({
        { text = "略過", roi = PLAYER },
        { text = "Skip", roi = PLAYER },
        { text = "Never Gonna Give", roi = below_player },
    }, 60000, 500)
    if not idx then fail("影片沒有開始播放") end
    if idx == 3 then break end
    log("略過廣告")
    input.tap(hit.cx, hit.cy)
    sleep(1000)
end

-- 5. 讀出目前時間 -------------------------------------------------------------
-- 點播放器叫出控制列（避開正中央的暫停鍵），在播放器下半部找「0:12 / 3:33」那一行
local function seconds(clock)
    local m, s = clock:match("(%d+):(%d+)")
    return tonumber(m) * 60 + tonumber(s)
end

local function find_time()
    for _ = 1, 3 do
        input.tap(W // 2, PLAYER_H * 3 // 4)
        sleep(300)
        for _, line in ipairs(vision.read_lines({ 0, PLAYER_H // 2, W, PLAYER_H // 2 })) do
            local now, total = line.text:match("(%d+:%d+)%s*/%s*(%d+:%d+)")
            if now then return line, seconds(now), seconds(total) end
        end
        sleep(1000)
    end
end

local label, now, total = find_time()
if not label then fail("讀不到播放時間") end
log(string.format("目前 %d 秒，全長 %d 秒", now, total))

-- 6. 拖曳進度條到一半 ---------------------------------------------------------
-- YouTube 的進度條是相對拖曳：手指移動的距離決定前進多少，跟按下的位置無關。
-- 所以從目前的位置按下，往目標的方向移「差距的比例 × 寬度」；YouTube 可能從上次的位置接著播，目標也可能在左邊。
local target = total // 2
local y = PLAYER_H - 4
local from_x = W * now // total
local to_x = W * target // total
input.down(1, from_x, y)
for step = 1, 10 do
    sleep(30)
    input.move(1, from_x + (to_x - from_x) * step // 10, y)
end
input.up(1)

-- 7. 確認快轉成功 -------------------------------------------------------------
-- 時間標籤還在剛才那個位置；用 vision.read 只讀那一小塊，比整片偵測快。
-- 框放寬一點，因為數字變長（0:09 → 1:46）時標籤會變寬。
sleep(300)
local reading = vision.read({ label.x - 8, label.y - 4, label.w + 40, label.h + 8 })
local now_text = reading and reading.text:match("(%d+:%d+)")
if not now_text then fail("快轉後讀不到時間") end
local after = seconds(now_text)
log(string.format("快轉後 %d 秒，目標 %d 秒", after, target))

-- 影片一直在播、拖曳也有誤差，差 10 秒內都算成功
if math.abs(after - target) > 10 then fail("快轉的位置不對：" .. now_text) end
data.set("position", now_text)
data.set("status", "never gonna give you up")
