#include "LuaBindings.h"
#include "JsonString.h"
#include "ScriptRuntime.h"
#include "UiModule.h"

#include <android/keycodes.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr long kDefaultWaitTimeoutMs = 10000;
constexpr long kDefaultTapHoldMs = 50;
constexpr long kDefaultSwipeDurationMs = 300;
/** vision.wait 的輪詢上限——沒有新影格時也要定期回來檢查逾時與停止旗標。 */
constexpr long kWaitPollMs = 200;

ScriptRuntime *self(lua_State *L) {
    return static_cast<ScriptRuntime *>(lua_touserdata(L, lua_upvalueindex(1)));
}

long nowMs() {
    return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
}

[[noreturn]] void raiseStopped(lua_State *L) {
    luaL_error(L, "script stopped");
    std::abort();  // luaL_error 不會回來，這行只是讓編譯器知道
}

// --- Lua 值 → C++ ---------------------------------------------------------

/** 讀出 `{x, y, w, h}` 或 `{x = .., y = .., w = .., h = ..}`。 */
bool readRect(lua_State *L, int index, cv::Rect &out) {
    if (!lua_istable(L, index)) return false;

    auto field = [&](const char *name, int arrayIndex) -> int {
        lua_getfield(L, index, name);
        if (!lua_isnumber(L, -1)) {
            lua_pop(L, 1);
            lua_rawgeti(L, index, arrayIndex);
        }
        int value = static_cast<int>(lua_tointeger(L, -1));
        lua_pop(L, 1);
        return value;
    };

    out.x = field("x", 1);
    out.y = field("y", 2);
    out.width = field("w", 3);
    out.height = field("h", 4);
    return out.width > 0 && out.height > 0;
}

/**
 * 讀出一個 vision request。接受完整的 table，或只給圖片路徑的字串簡寫。
 * 路徑相對腳本資料夾解析。`image` 與 `text` 擇一；`scale`/`gray` 只用於模板。
 */
VisionRequest readRequest(lua_State *L, int index, ScriptRuntime *runtime) {
    VisionRequest request;

    if (lua_isstring(L, index)) {
        request.imagePath = runtime->vision().resolvePath(lua_tostring(L, index));
        return request;
    }

    luaL_checktype(L, index, LUA_TTABLE);

    lua_getfield(L, index, "image");
    bool hasImage = lua_isstring(L, -1);
    if (hasImage) request.imagePath = runtime->vision().resolvePath(lua_tostring(L, -1));
    lua_pop(L, 1);

    lua_getfield(L, index, "text");
    bool hasText = lua_isstring(L, -1);
    if (hasText) request.text = lua_tostring(L, -1);
    lua_pop(L, 1);

    if (hasImage == hasText) {
        luaL_error(L, "vision request needs exactly one of `image` (a path relative to the script) or `text`");
    }
    if (hasText && request.text.empty()) {
        luaL_error(L, "vision request `text` must not be empty");
    }

    lua_getfield(L, index, "threshold");
    if (lua_isnumber(L, -1)) request.threshold = lua_tonumber(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, index, "scale");
    if (!lua_isnil(L, -1) && hasText) luaL_error(L, "`scale` only applies to image requests");
    if (lua_isnumber(L, -1)) {
        double scale = lua_tonumber(L, -1);
        if (scale > 0.0 && scale <= 1.0) request.scale = scale;
    }
    lua_pop(L, 1);

    lua_getfield(L, index, "gray");
    if (!lua_isnil(L, -1) && hasText) luaL_error(L, "`gray` only applies to image requests");
    if (lua_isboolean(L, -1)) request.gray = lua_toboolean(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, index, "exact");
    if (!lua_isnil(L, -1) && hasImage) luaL_error(L, "`exact` only applies to text requests");
    if (lua_isboolean(L, -1)) request.exact = lua_toboolean(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, index, "roi");
    request.hasRoi = readRect(L, lua_gettop(L), request.roi);
    lua_pop(L, 1);

    return request;
}

void pushHit(lua_State *L, const VisionHit &hit) {
    lua_newtable(L);
    auto set = [&](const char *name, double value) {
        lua_pushnumber(L, value);
        lua_setfield(L, -2, name);
    };
    set("x", hit.x);
    set("y", hit.y);
    set("w", hit.w);
    set("h", hit.h);
    set("cx", hit.cx);
    set("cy", hit.cy);
    set("confidence", hit.confidence);
    if (!hit.text.empty()) {
        lua_pushstring(L, hit.text.c_str());
        lua_setfield(L, -2, "text");
    }
}

/** `vision.read`/`read_lines` 的一項（TextLine）：confidence 是 OCR 的信心度。 */
void pushTextLine(lua_State *L, const OcrLine &line) {
    lua_newtable(L);
    lua_pushstring(L, line.text.c_str());
    lua_setfield(L, -2, "text");
    auto set = [&](const char *name, double value) {
        lua_pushnumber(L, value);
        lua_setfield(L, -2, name);
    };
    set("confidence", line.confidence);
    set("x", line.box.x);
    set("y", line.box.y);
    set("w", line.box.width);
    set("h", line.box.height);
    set("cx", line.box.x + line.box.width / 2.0);
    set("cy", line.box.y + line.box.height / 2.0);
}

/** 把這一輪的比對結果回報給 Kotlin，讓 Scripts UI 看得到最後一次比對發生了什麼。 */
void reportHits(ScriptRuntime *runtime, const std::vector<VisionRequest> &requests,
                const std::vector<VisionHit> &hits) {
    std::ostringstream out;
    out << '[';
    for (size_t i = 0; i < hits.size(); i++) {
        const VisionHit &hit = hits[i];
        if (i > 0) out << ',';
        const std::string &name = requests[i].isText() ? "text:" + requests[i].text : requests[i].imagePath;
        out << "{\"name\":" << jsonString(name)
            << ",\"found\":" << (hit.found ? "true" : "false")
            << ",\"x\":" << hit.x << ",\"y\":" << hit.y
            << ",\"width\":" << hit.w << ",\"height\":" << hit.h
            << ",\"cx\":" << hit.cx << ",\"cy\":" << hit.cy
            << ",\"confidence\":" << hit.confidence << '}';
    }
    out << ']';
    runtime->pushEvent(EngineEventType::VISION_RESULT, out.str());
}

void requireVision(lua_State *L, ScriptRuntime *runtime) {
    if (runtime->isPhysicalDisplay() && !runtime->isMirrorActive(L)) {
        luaL_error(L, "vision on physical display requires active mirror (call screen.start_mirror() first)");
    }
    if (!runtime->hasVision()) {
        luaL_error(L, "vision is unavailable: run the script on a virtual display or call screen.start_mirror() on physical display");
    }
    runtime->awaitFirstFrameIfPending();
}

void requireInput(lua_State *L, ScriptRuntime *runtime) {
    if (runtime->isPhysicalDisplay() && !runtime->isMirrorActive(L)) {
        luaL_error(L, "input on physical display requires active mirror (call screen.start_mirror() first)");
    }
}

/** 有文字請求時載入 OCR；未安裝或載入失敗直接報錯。沒有文字請求回傳 null。 */
Ocr *ocrFor(lua_State *L, ScriptRuntime *runtime, const std::vector<VisionRequest> &requests) {
    bool anyText = std::any_of(requests.begin(), requests.end(),
                               [](const VisionRequest &r) { return r.isText(); });
    if (!anyText) return nullptr;
    std::string error;
    Ocr *ocr = runtime->ocr(error);
    if (ocr == nullptr) luaL_error(L, "%s", error.c_str());
    return ocr;
}

/**
 * 把 [body] 拋出的 C++ 例外（ORT 以例外回報錯誤）轉成帶訊息的 Lua error。讓它直接穿出 binding
 * 的話，Lua 的 `catch (...)` 會把它當成一個沒有錯誤物件的錯誤，腳本看不到原因。
 */
template<typename Body>
void guarded(lua_State *L, Body body) {
    std::string error;
    try {
        body();
        return;
    } catch (const std::exception &e) {
        error = e.what();
    }
    luaL_error(L, "OCR failed: %s", error.c_str());
}

/** 比對一次，把第一個命中的結果推上堆疊。@return 命中的 request 索引，沒有就回傳 -1。 */
int matchOnce(lua_State *L, ScriptRuntime *runtime, const std::vector<VisionRequest> &requests, Ocr *ocr) {
    std::vector<VisionHit> hits;
    guarded(L, [&] { hits = runtime->vision().match(requests, ocr); });
    reportHits(runtime, requests, hits);

    for (size_t i = 0; i < hits.size(); i++) {
        if (hits[i].found) {
            pushHit(L, hits[i]);
            return static_cast<int>(i);
        }
    }
    return -1;
}

/**
 * wait 系列共用的輪詢：每張新影格呼叫一次 [attempt]，它回傳 true 就停，或撐到 [timeoutMs] 為止。
 * @param stepMs 兩次呼叫開始的最小間隔；0 表示每張新影格都呼叫。
 * @return attempt 是否回傳過 true。
 */
template<typename Attempt>
bool pollFrames(lua_State *L, ScriptRuntime *runtime, long timeoutMs, long stepMs, Attempt attempt) {
    long deadline = nowMs() + std::max(0L, timeoutMs);
    // 同一張影格看過就不再看：畫面靜止時 distributor 不送新影格，重比只會得到同樣的結果。
    bool attempted = false;
    uint64_t matched = 0;

    while (true) {
        if (!runtime->isRunning()) raiseStopped(L);

        long attemptStart = nowMs();
        uint64_t seen = runtime->vision().frameCounter();
        if (!attempted || seen != matched) {
            if (attempt()) return true;
            attempted = true;
            matched = seen;
        }

        long remaining = deadline - nowMs();
        if (remaining <= 0) return false;

        if (stepMs > 0) {
            long pause = std::min(attemptStart + stepMs - nowMs(), remaining);
            if (pause > 0 && !runtime->interruptibleSleep(pause)) raiseStopped(L);
            remaining = deadline - nowMs();
            // 睡到 deadline 就直接回迴圈頂端做最後一次，與不帶 step 時逾時前的最後一次一致。
            if (remaining <= 0) continue;
        }

        // 等新影格；逾時就回到迴圈頂端重新檢查 deadline 與停止旗標。
        runtime->vision().waitForFrameAfter(seen, std::min(remaining, kWaitPollMs));
    }
}

/**
 * 比對到第一個命中就停，或撐到 [timeoutMs] 為止。
 * @return 命中的 request 索引，沒有就回傳 -1。
 */
int matchUntil(lua_State *L, ScriptRuntime *runtime, const std::vector<VisionRequest> &requests,
               long timeoutMs, long stepMs) {
    Ocr *ocr = ocrFor(L, runtime, requests);
    int index = -1;
    pollFrames(L, runtime, timeoutMs, stepMs, [&] {
        index = matchOnce(L, runtime, requests, ocr);
        return index >= 0;
    });
    return index;
}

/** wait_until 的 fn 執行期間影格是固定的，在裡面等新影格永遠等不到。 */
void rejectInsideWaitUntil(lua_State *L, ScriptRuntime *runtime, const char *function) {
    if (runtime->vision().isFramePinned()) {
        luaL_error(L, "%s cannot be called inside vision.wait_until", function);
    }
}

/** 解構時解除固定：fn 拋錯（含停止）時 Lua 的錯誤是 C++ 例外，一樣會經過這裡。 */
class FramePin {
public:
    explicit FramePin(VisionMatcher &vision) : vision(vision) { vision.pinFrame(); }
    ~FramePin() { vision.unpinFrame(); }
    FramePin(const FramePin &) = delete;
    FramePin &operator=(const FramePin &) = delete;

private:
    VisionMatcher &vision;
};

/** 讀出 `requests` 陣列；空陣列直接報錯，錯誤訊息帶上呼叫的函式名。 */
std::vector<VisionRequest> readRequestList(lua_State *L, int index, ScriptRuntime *runtime,
                                           const char *function) {
    luaL_checktype(L, index, LUA_TTABLE);

    std::vector<VisionRequest> requests;
    auto count = static_cast<int>(lua_rawlen(L, index));
    for (int i = 1; i <= count; i++) {
        lua_rawgeti(L, index, i);
        requests.push_back(readRequest(L, lua_gettop(L), runtime));
        lua_pop(L, 1);
    }
    if (requests.empty()) {
        luaL_error(L, "%s needs at least one request", function);
    }
    return requests;
}

long readTimeout(lua_State *L, int index) {
    return static_cast<long>(luaL_optinteger(L, index, kDefaultWaitTimeoutMs));
}

long readStep(lua_State *L, int index) {
    auto step = static_cast<long>(luaL_optinteger(L, index, 0));
    luaL_argcheck(L, step >= 0, index, "step_ms must not be negative");
    return step;
}

/** matchOnce/matchUntil 已把命中的 table 推上堆疊；索引放前面，回傳 index, hit。 */
int pushIndexedHit(lua_State *L, int index) {
    if (index < 0) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushinteger(L, index + 1);
    lua_insert(L, -2);
    return 2;
}

// --- 全域 ------------------------------------------------------------------

int lua_log(lua_State *L) {
    int count = lua_gettop(L);
    std::string message;
    for (int i = 1; i <= count; i++) {
        if (i > 1) message += '\t';
        if (lua_isstring(L, i)) {
            message += lua_tostring(L, i);
        } else {
            luaL_tolstring(L, i, nullptr);
            message += lua_tostring(L, -1);
            lua_pop(L, 1);
        }
    }
    __android_log_print(ANDROID_LOG_DEBUG, "LuaScript", "%s", message.c_str());

    ScriptRuntime *runtime = self(L);
    LocalRef<jstring> jMessage(runtime->env(), runtime->env()->NewStringUTF(message.c_str()));
    runtime->hostVoid(L, "log", runtime->host().log, jMessage.get());
    return 0;
}

int lua_sleep(lua_State *L) {
    auto ms = static_cast<long>(luaL_checkinteger(L, 1));
    if (!self(L)->interruptibleSleep(ms)) raiseStopped(L);
    return 0;
}

// --- screen ----------------------------------------------------------------

int lua_screen_start_mirror(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    lua_pushboolean(L, runtime->startMirror(L));
    return 1;
}

int lua_screen_stop_mirror(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    lua_pushboolean(L, runtime->stopMirror(L));
    return 1;
}

/**
 * `screen` 走 __index 而不是固定值：`has_vision`/`is_mirror_active` 確實是即時算的。
 * `width`/`height`/`rotation` 這三個其實整場執行都固定（見 VisionMatcher 的建構子），
 * 用同一條路徑只是因為都是唯讀屬性，不是因為它們會變。
 */
int lua_screen_index(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *key = luaL_checkstring(L, 2);

    int width = 0, height = 0;
    runtime->vision().logicalSize(width, height);

    if (strcmp(key, "width") == 0) {
        lua_pushinteger(L, width);
    } else if (strcmp(key, "height") == 0) {
        lua_pushinteger(L, height);
    } else if (strcmp(key, "rotation") == 0) {
        lua_pushinteger(L, runtime->vision().rotation());
    } else if (strcmp(key, "has_vision") == 0) {
        lua_pushboolean(L, runtime->hasVision());
    } else if (strcmp(key, "is_mirror_active") == 0) {
        lua_pushboolean(L, runtime->isMirrorActive(L));
    } else {
        lua_pushnil(L);
    }
    return 1;
}

// --- vision ----------------------------------------------------------------

// 請求先解析再檢查影格來源：參數寫錯時不論跑在哪裡都報同一個錯。

int lua_vision_find(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    requireVision(L, runtime);
    if (matchOnce(L, runtime, requests, ocrFor(L, runtime, requests)) < 0) {
        lua_pushnil(L);
    }
    return 1;
}

int lua_vision_find_any(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests = readRequestList(L, 1, runtime, "vision.find_any");
    requireVision(L, runtime);
    return pushIndexedHit(L, matchOnce(L, runtime, requests, ocrFor(L, runtime, requests)));
}

int lua_vision_find_all(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    requireVision(L, runtime);
    Ocr *ocr = ocrFor(L, runtime, requests);

    std::vector<VisionHit> hits;
    guarded(L, [&] { hits = runtime->vision().findAll(requests[0], ocr); });
    reportHits(runtime, requests, {hits.empty() ? VisionHit{} : hits[0]});

    lua_createtable(L, static_cast<int>(hits.size()), 0);
    for (size_t i = 0; i < hits.size(); i++) {
        pushHit(L, hits[i]);
        lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));
    }
    return 1;
}

int lua_vision_wait(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    rejectInsideWaitUntil(L, runtime, "vision.wait");
    requireVision(L, runtime);
    if (matchUntil(L, runtime, requests, readTimeout(L, 2), readStep(L, 3)) < 0) {
        lua_pushnil(L);
    }
    return 1;
}

int lua_vision_wait_any(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests = readRequestList(L, 1, runtime, "vision.wait_any");
    rejectInsideWaitUntil(L, runtime, "vision.wait_any");
    requireVision(L, runtime);
    return pushIndexedHit(L, matchUntil(L, runtime, requests, readTimeout(L, 2), readStep(L, 3)));
}

/**
 * 每張新影格呼叫一次 fn，fn 期間影格固定（同一區域的文字辨識只做一次）；fn 回傳 truthy 就把
 * 那個值回傳，逾時回傳 nil。fn 拋的錯照常往外拋。
 */
int lua_vision_wait_until(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    luaL_checktype(L, 1, LUA_TFUNCTION);
    long timeoutMs = readTimeout(L, 2);
    long stepMs = readStep(L, 3);
    rejectInsideWaitUntil(L, runtime, "vision.wait_until");
    requireVision(L, runtime);

    bool done = pollFrames(L, runtime, timeoutMs, stepMs, [&] {
        FramePin pin(runtime->vision());
        lua_pushvalue(L, 1);
        lua_call(L, 0, 1);
        if (lua_toboolean(L, -1)) return true;
        lua_pop(L, 1);
        return false;
    });
    if (!done) lua_pushnil(L);
    return 1;
}

/** read/read_lines 共用：讀最新影格的文字。[roiIndex] 沒給（nil/none）時是整張。 */
std::vector<OcrLine> readText(lua_State *L, ScriptRuntime *runtime, int roiIndex, bool roiRequired, bool detect) {
    cv::Rect roi;
    bool hasRoi = false;
    if (!lua_isnoneornil(L, roiIndex)) {
        luaL_checktype(L, roiIndex, LUA_TTABLE);
        hasRoi = readRect(L, roiIndex, roi);
        if (!hasRoi) luaL_argerror(L, roiIndex, "roi needs a positive width and height");
    } else if (roiRequired) {
        luaL_argerror(L, roiIndex, "roi {x, y, w, h} is required");
    }
    requireVision(L, runtime);

    std::string error;
    Ocr *ocr = runtime->ocr(error);
    if (ocr == nullptr) luaL_error(L, "%s", error.c_str());

    std::vector<OcrLine> lines;
    guarded(L, [&] { lines = runtime->vision().readText(*ocr, hasRoi, roi, detect); });
    return lines;
}

int lua_vision_read(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<OcrLine> lines = readText(L, runtime, 1, true, false);
    if (lines.empty()) {
        lua_pushnil(L);
    } else {
        pushTextLine(L, lines[0]);
    }
    return 1;
}

int lua_vision_read_lines(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<OcrLine> lines = readText(L, runtime, 1, false, true);
    lua_createtable(L, static_cast<int>(lines.size()), 0);
    for (size_t i = 0; i < lines.size(); i++) {
        pushTextLine(L, lines[i]);
        lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));
    }
    return 1;
}

// --- input -----------------------------------------------------------------

/** 呼叫 ScriptHost.swipe。points 是攤平的 [x1,y1,x2,y2,...]。 */
void callSwipe(lua_State *L, ScriptRuntime *runtime, const char *function, int pointerId,
               const std::vector<int> &points, long durationMs, bool keep) {
    JNIEnv *env = runtime->env();
    auto size = static_cast<jsize>(points.size());
    LocalRef<jintArray> jPoints(env, env->NewIntArray(size));
    env->SetIntArrayRegion(jPoints.get(), 0, size, points.data());
    runtime->hostVoid(L, function, runtime->host().swipe,
                      pointerId, jPoints.get(), (jlong) durationMs, (jboolean) keep);
}

/** 讀出 `{x1,y1,x2,y2,...}` 或 `{{x1,y1},{x2,y2},...}`。 */
std::vector<int> readPoints(lua_State *L, int index) {
    luaL_checktype(L, index, LUA_TTABLE);
    std::vector<int> points;
    auto count = static_cast<int>(lua_rawlen(L, index));
    for (int i = 1; i <= count; i++) {
        lua_rawgeti(L, index, i);
        if (lua_istable(L, -1)) {
            auto inner = static_cast<int>(lua_rawlen(L, -1));
            for (int j = 1; j <= inner; j++) {
                lua_rawgeti(L, -1, j);
                points.push_back(static_cast<int>(luaL_checknumber(L, -1)));
                lua_pop(L, 1);
            }
        } else {
            points.push_back(static_cast<int>(luaL_checknumber(L, -1)));
        }
        lua_pop(L, 1);
    }
    if (points.size() < 2 || points.size() % 2 != 0) {
        luaL_error(L, "expected a list of x,y pairs");
    }
    return points;
}

int lua_input_tap(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto x = static_cast<int>(luaL_checknumber(L, 1));
    auto y = static_cast<int>(luaL_checknumber(L, 2));
    auto hold = static_cast<long>(luaL_optinteger(L, 3, kDefaultTapHoldMs));

    callSwipe(L, runtime, "input.tap", -1, {x, y, x, y}, hold, false);
    // 注入是非同步的；線性腳本要的是「點完才往下走」，所以在這裡等它跑完。
    if (!runtime->interruptibleSleep(hold)) raiseStopped(L);
    return 0;
}

int lua_input_swipe(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    std::vector<int> points = readPoints(L, 1);
    auto duration = static_cast<long>(luaL_optinteger(L, 2, kDefaultSwipeDurationMs));

    callSwipe(L, runtime, "input.swipe", -1, points, duration, false);
    if (!runtime->interruptibleSleep(duration)) raiseStopped(L);
    return 0;
}

int lua_input_multi_swipe(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    luaL_checktype(L, 1, LUA_TTABLE);
    auto duration = static_cast<long>(luaL_optinteger(L, 2, kDefaultSwipeDurationMs));

    // 先全部發出去（注入是非同步的，所以它們會同時進行），最後才一次等完。
    lua_pushnil(L);
    while (lua_next(L, 1) != 0) {
        auto pointerId = static_cast<int>(luaL_checkinteger(L, -2));
        std::vector<int> points = readPoints(L, lua_gettop(L));
        callSwipe(L, runtime, "input.multi_swipe", pointerId, points, duration, false);
        lua_pop(L, 1);
    }

    if (!runtime->interruptibleSleep(duration)) raiseStopped(L);
    return 0;
}

int lua_input_down(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    auto x = static_cast<int>(luaL_checknumber(L, 2));
    auto y = static_cast<int>(luaL_checknumber(L, 3));
    callSwipe(L, runtime, "input.down", id, {x, y}, 0, true);
    return 0;
}

int lua_input_move(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    auto x = static_cast<int>(luaL_checknumber(L, 2));
    auto y = static_cast<int>(luaL_checknumber(L, 3));
    callSwipe(L, runtime, "input.move", id, {x, y}, 0, true);
    return 0;
}

int lua_input_up(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    runtime->hostVoid(L, "input.up", runtime->host().pointerUp, id);
    return 0;
}

bool callKey(lua_State *L, ScriptRuntime *runtime, const char *function, int keyCode) {
    requireInput(L, runtime);
    return runtime->hostBoolean(L, function, runtime->host().key, keyCode);
}

int lua_input_key(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), "input.key", static_cast<int>(luaL_checkinteger(L, 1))));
    return 1;
}

/**
 * 整串先檢查完才注入：含不支援的字元時拋錯，一個字都不打。允許的集合就是 VIRTUAL_KEYBOARD
 * 的 KeyCharacterMap 對得到的部分；UTF-8 的多 byte 字元每個 byte 都 ≥ 0x80，會在第一個 byte 被擋下。
 */
int lua_input_text(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    size_t length = 0;
    const char *text = luaL_checklstring(L, 1, &length);
    requireInput(L, runtime);
    for (size_t i = 0; i < length; ++i) {
        auto c = static_cast<unsigned char>(text[i]);
        if ((c < 0x20 || c > 0x7E) && c != '\n' && c != '\t') {
            return luaL_error(L, "input.text: unsupported character at position %d (0x%02X); "
                                 "only printable ASCII, \\n and \\t are supported",
                              static_cast<int>(i + 1), c);
        }
    }
    if (length == 0) {
        lua_pushboolean(L, true);
        return 1;
    }
    LocalRef<jstring> jText(runtime->env(), runtime->env()->NewStringUTF(text));
    lua_pushboolean(L, runtime->hostBoolean(L, "input.text", runtime->host().text, jText.get()));
    return 1;
}

int lua_input_back(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), "input.back", AKEYCODE_BACK));
    return 1;
}

int lua_input_home(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), "input.home", AKEYCODE_HOME));
    return 1;
}

int lua_input_recents(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), "input.recents", AKEYCODE_APP_SWITCH));
    return 1;
}

// --- app / device / data ---------------------------------------------------

int lua_app_launch(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *package = luaL_checkstring(L, 1);
    LocalRef<jstring> jPackage(runtime->env(), runtime->env()->NewStringUTF(package));
    lua_pushboolean(L, runtime->hostBoolean(L, "app.launch", runtime->host().launch, jPackage.get()));
    return 1;
}

// muted 必填：變數打錯字傳進 nil 時若預設為靜音，狀態又活得比腳本久，很難察覺。
int lua_app_mute(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *package = luaL_checkstring(L, 1);
    luaL_checktype(L, 2, LUA_TBOOLEAN);
    bool muted = lua_toboolean(L, 2);
    LocalRef<jstring> jPackage(runtime->env(), runtime->env()->NewStringUTF(package));
    lua_pushboolean(L, runtime->hostBoolean(L, "app.mute", runtime->host().mute, jPackage.get(), (jboolean) muted));
    return 1;
}

int lua_app_list(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    JNIEnv *env = runtime->env();
    LocalRef<jobjectArray> tasks(
            env, (jobjectArray) runtime->hostObject(L, "app.list", runtime->host().listApps));

    const AppTaskFields &fields = runtime->appTaskFields();
    jsize count = env->GetArrayLength(tasks.get());
    lua_createtable(L, count, 0);
    for (jsize i = 0; i < count; i++) {
        LocalRef<jobject> task(env, env->GetObjectArrayElement(tasks.get(), i));
        LocalRef<jstring> jPackage(env, (jstring) env->GetObjectField(task.get(), fields.packageName));
        const char *chars = env->GetStringUTFChars(jPackage.get(), nullptr);
        std::string package(chars);
        env->ReleaseStringUTFChars(jPackage.get(), chars);

        lua_createtable(L, 0, 2);
        lua_pushstring(L, package.c_str());
        lua_setfield(L, -2, "package");
        lua_pushinteger(L, env->GetIntField(task.get(), fields.displayId));
        lua_setfield(L, -2, "display_id");
        lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));
    }
    return 1;
}

int lua_device_notify(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *title = luaL_checkstring(L, 1);
    const char *text = luaL_optstring(L, 2, "");
    JNIEnv *env = runtime->env();
    LocalRef<jstring> jTitle(env, env->NewStringUTF(title));
    LocalRef<jstring> jText(env, env->NewStringUTF(text));
    runtime->hostVoid(L, "device.notify", runtime->host().notify, jTitle.get(), jText.get());
    return 0;
}

int lua_device_open_uri(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *uri = luaL_checkstring(L, 1);
    LocalRef<jstring> jUri(runtime->env(), runtime->env()->NewStringUTF(uri));
    runtime->hostVoid(L, "device.open_uri", runtime->host().openUri, jUri.get());
    return 0;
}

int lua_data_set(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *key = luaL_checkstring(L, 1);
    JNIEnv *env = runtime->env();
    LocalRef<jstring> jKey(env, env->NewStringUTF(key));
    LocalRef<jobject> jValue(env, runtime->boxLuaValue(L, 2));
    runtime->hostVoid(L, "data.set", runtime->host().setData, jKey.get(), jValue.get());
    return 0;
}

// --- ui --------------------------------------------------------------------

/** `ui` 模組取樹用，不公開給腳本。[1] 是寫進錯誤訊息的函式名，回傳 JSON 字串。 */
int lua_ui_tree_json(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *function = luaL_checkstring(L, 1);
    JNIEnv *env = runtime->env();
    LocalRef<jbyteArray> bytes(env, (jbyteArray) runtime->hostObject(L, function, runtime->host().viewTree));
    jsize length = env->GetArrayLength(bytes.get());
    std::string json(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes.get(), 0, length, reinterpret_cast<jbyte *>(json.data()));
    lua_pushlstring(L, json.data(), json.size());
    return 1;
}

int lua_ui_now_ms(lua_State *L) {
    lua_pushinteger(L, nowMs());
    return 1;
}

/** 執行 [kUiModuleSource]，把它回傳的表設成全域 `ui`。 */
void registerUi(lua_State *L, ScriptRuntime *runtime) {
    if (luaL_loadbuffer(L, kUiModuleSource, strlen(kUiModuleSource), "=ui") != LUA_OK) {
        lua_error(L);
    }
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_ui_tree_json, 1);
    lua_pushcfunction(L, lua_ui_now_ms);
    lua_getfield(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, "cjson");
    lua_getfield(L, -1, "decode");
    lua_replace(L, -3);  // decode 取代 package.loaded
    lua_pop(L, 1);       // cjson 表
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_sleep, 1);
    lua_call(L, 4, 1);
    lua_setglobal(L, "ui");
}

// --- 註冊 ------------------------------------------------------------------

/** 把 [functions] 註冊成一個全域表，每個函式都帶 runtime 當 upvalue。 */
void registerModule(lua_State *L, ScriptRuntime *runtime, const char *name,
                    const luaL_Reg *functions) {
    lua_newtable(L);
    for (const luaL_Reg *entry = functions; entry->name != nullptr; entry++) {
        lua_pushlightuserdata(L, runtime);
        lua_pushcclosure(L, entry->func, 1);
        lua_setfield(L, -2, entry->name);
    }
    lua_setglobal(L, name);
}

const luaL_Reg kVision[] = {
        {"find",     lua_vision_find},
        {"find_any", lua_vision_find_any},
        {"find_all", lua_vision_find_all},
        {"read",     lua_vision_read},
        {"read_lines", lua_vision_read_lines},
        {"wait",     lua_vision_wait},
        {"wait_any", lua_vision_wait_any},
        {"wait_until", lua_vision_wait_until},
        {nullptr, nullptr},
};

const luaL_Reg kInput[] = {
        {"tap",         lua_input_tap},
        {"swipe",       lua_input_swipe},
        {"multi_swipe", lua_input_multi_swipe},
        {"down",        lua_input_down},
        {"move",        lua_input_move},
        {"up",          lua_input_up},
        {"key",         lua_input_key},
        {"text",        lua_input_text},
        {"back",        lua_input_back},
        {"home",        lua_input_home},
        {"recents",     lua_input_recents},
        {nullptr, nullptr},
};

const luaL_Reg kApp[] = {
        {"launch", lua_app_launch},
        {"list",   lua_app_list},
        {"mute",   lua_app_mute},
        {nullptr, nullptr},
};

const luaL_Reg kDevice[] = {
        {"notify",   lua_device_notify},
        {"open_uri", lua_device_open_uri},
        {nullptr, nullptr},
};

const luaL_Reg kData[] = {
        {"set", lua_data_set},
        {nullptr, nullptr},
};

}  // namespace

namespace moonclicker {

void registerApi(lua_State *L, ScriptRuntime *runtime) {
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_log, 1);
    lua_setglobal(L, "log");

    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_sleep, 1);
    lua_setglobal(L, "sleep");

    // screen 是表 + __index，start_mirror / stop_mirror 放在表上，屬性走 __index。
    lua_newtable(L);
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_screen_start_mirror, 1);
    lua_setfield(L, -2, "start_mirror");

    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_screen_stop_mirror, 1);
    lua_setfield(L, -2, "stop_mirror");

    lua_newtable(L);
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_screen_index, 1);
    lua_setfield(L, -2, "__index");
    lua_setmetatable(L, -2);
    lua_setglobal(L, "screen");

    registerModule(L, runtime, "vision", kVision);
    registerModule(L, runtime, "input", kInput);
    registerModule(L, runtime, "app", kApp);
    registerModule(L, runtime, "device", kDevice);
    registerModule(L, runtime, "data", kData);
    registerUi(L, runtime);
}

}  // namespace moonclicker
