#include "LuaBindings.h"
#include "ScriptRuntime.h"

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

std::string jsonEscape(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    for (char c: s) {
        if (c == '"' || c == '\\') out += '\\';
        out += c;
    }
    return out;
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
 * 路徑相對腳本資料夾解析。`image` 與 `text` 擇一；`scale`／`gray` 只用於模板。
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

/** `vision.read`／`read_lines` 的一項（TextLine）：confidence 是 OCR 的信心度。 */
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
        out << "{\"name\":\"" << jsonEscape(name)
            << "\",\"found\":" << (hit.found ? "true" : "false")
            << ",\"x\":" << hit.x << ",\"y\":" << hit.y
            << ",\"width\":" << hit.w << ",\"height\":" << hit.h
            << ",\"cx\":" << hit.cx << ",\"cy\":" << hit.cy
            << ",\"confidence\":" << hit.confidence << '}';
    }
    out << ']';
    runtime->pushEvent(EngineEventType::VISION_RESULT, out.str());
}

void requireVision(lua_State *L, ScriptRuntime *runtime) {
    if (runtime->isPhysicalDisplay() && !runtime->isMirrorActive()) {
        luaL_error(L, "vision on physical display requires active mirror (call screen.start_mirror() first)");
    }
    if (!runtime->hasVision()) {
        luaL_error(L, "vision is unavailable: run the script on a virtual display or call screen.start_mirror() on physical display");
    }
}

void requireInput(lua_State *L, ScriptRuntime *runtime) {
    if (runtime->isPhysicalDisplay() && !runtime->isMirrorActive()) {
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
 * 在 Lua 與 C++ 例外的邊界執行 [body]：ORT 以 C++ 例外回報錯誤，而 Lua 以 longjmp 收錯，
 * 例外不能穿過 Lua 堆疊。先在 try 內把訊息存起來，離開 catch 之後才 luaL_error。
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
 * wait 系列共用的核心：比對到第一個命中就停，或撐到 [timeoutMs] 為止。
 * @param stepMs 兩次比對開始的最小間隔；0 表示每張新影格都比對。
 * @return 命中的 request 索引，沒有就回傳 -1。
 */
int matchUntil(lua_State *L, ScriptRuntime *runtime, const std::vector<VisionRequest> &requests,
               long timeoutMs, long stepMs) {
    Ocr *ocr = ocrFor(L, runtime, requests);
    long deadline = nowMs() + std::max(0L, timeoutMs);

    while (true) {
        if (!runtime->isRunning()) raiseStopped(L);

        long attemptStart = nowMs();
        uint64_t seen = runtime->vision().frameCounter();
        int index = matchOnce(L, runtime, requests, ocr);
        if (index >= 0) return index;

        long remaining = deadline - nowMs();
        if (remaining <= 0) return -1;

        if (stepMs > 0) {
            long pause = std::min(attemptStart + stepMs - nowMs(), remaining);
            if (pause > 0 && !runtime->interruptibleSleep(pause)) raiseStopped(L);
            remaining = deadline - nowMs();
            // 睡到 deadline 就直接回迴圈頂端做最後一次比對，與不帶 step 時逾時前的最後一次一致。
            if (remaining <= 0) continue;
        }

        // 等新影格；逾時就回到迴圈頂端重新檢查 deadline 與停止旗標。
        runtime->vision().waitForFrameAfter(seen, std::min(remaining, kWaitPollMs));
    }
}

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

/** matchOnce／matchUntil 已把命中的 table 推上堆疊；索引放前面，回傳 index, hit。 */
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
    JNIEnv *env = runtime->env();
    if (env != nullptr) {
        jstring jMessage = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(runtime->hostObject(), runtime->host().log, jMessage);
        env->DeleteLocalRef(jMessage);
    }
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
    lua_pushboolean(L, runtime->startMirror());
    return 1;
}

int lua_screen_stop_mirror(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    lua_pushboolean(L, runtime->stopMirror());
    return 1;
}

/**
 * `screen` 走 __index 而不是固定值：`has_vision`／`is_mirror_active` 確實是即時算的。
 * `width`／`height`／`rotation` 這三個其實整場執行都固定（見 VisionMatcher 的建構子），
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
        lua_pushboolean(L, runtime->isMirrorActive());
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

int lua_vision_wait(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    requireVision(L, runtime);
    if (matchUntil(L, runtime, requests, readTimeout(L, 2), readStep(L, 3)) < 0) {
        lua_pushnil(L);
    }
    return 1;
}

int lua_vision_wait_any(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    std::vector<VisionRequest> requests = readRequestList(L, 1, runtime, "vision.wait_any");
    requireVision(L, runtime);
    return pushIndexedHit(L, matchUntil(L, runtime, requests, readTimeout(L, 2), readStep(L, 3)));
}

/** read／read_lines 共用：讀最新影格的文字。[roiIndex] 沒給（nil／none）時是整張。 */
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
bool callSwipe(ScriptRuntime *runtime, int pointerId, const std::vector<int> &points,
               long durationMs, bool keep) {
    JNIEnv *env = runtime->env();
    if (env == nullptr) return false;

    auto size = static_cast<jsize>(points.size());
    jintArray jPoints = env->NewIntArray(size);
    env->SetIntArrayRegion(jPoints, 0, size, points.data());
    jboolean ok = env->CallBooleanMethod(runtime->hostObject(), runtime->host().swipe,
                                         pointerId, jPoints, (jlong) durationMs, (jboolean) keep);
    env->DeleteLocalRef(jPoints);
    return ok;
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

    callSwipe(runtime, -1, {x, y, x, y}, hold, false);
    // 注入是非同步的；線性腳本要的是「點完才往下走」，所以在這裡等它跑完。
    if (!runtime->interruptibleSleep(hold)) raiseStopped(L);
    return 0;
}

int lua_input_swipe(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    std::vector<int> points = readPoints(L, 1);
    auto duration = static_cast<long>(luaL_optinteger(L, 2, kDefaultSwipeDurationMs));

    callSwipe(runtime, -1, points, duration, false);
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
        callSwipe(runtime, pointerId, points, duration, false);
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
    callSwipe(runtime, id, {x, y}, 0, true);
    return 0;
}

int lua_input_move(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    auto x = static_cast<int>(luaL_checknumber(L, 2));
    auto y = static_cast<int>(luaL_checknumber(L, 3));
    callSwipe(runtime, id, {x, y}, 0, true);
    return 0;
}

int lua_input_up(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireInput(L, runtime);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    JNIEnv *env = runtime->env();
    if (env) env->CallBooleanMethod(runtime->hostObject(), runtime->host().pointerUp, id);
    return 0;
}

bool callKey(lua_State *L, ScriptRuntime *runtime, int keyCode) {
    requireInput(L, runtime);
    JNIEnv *env = runtime->env();
    if (env == nullptr) return false;
    return env->CallBooleanMethod(runtime->hostObject(), runtime->host().key, keyCode);
}

int lua_input_key(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), static_cast<int>(luaL_checkinteger(L, 1))));
    return 1;
}

int lua_input_back(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), AKEYCODE_BACK));
    return 1;
}

int lua_input_home(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), AKEYCODE_HOME));
    return 1;
}

int lua_input_recents(lua_State *L) {
    lua_pushboolean(L, callKey(L, self(L), AKEYCODE_APP_SWITCH));
    return 1;
}

// --- app / device / data ---------------------------------------------------

int lua_app_launch(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *package = luaL_checkstring(L, 1);
    JNIEnv *env = runtime->env();
    if (env == nullptr) {
        lua_pushboolean(L, false);
        return 1;
    }
    jstring jPackage = env->NewStringUTF(package);
    jboolean ok = env->CallBooleanMethod(runtime->hostObject(), runtime->host().launch, jPackage);
    env->DeleteLocalRef(jPackage);
    lua_pushboolean(L, ok);
    return 1;
}

int lua_app_list(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    JNIEnv *env = runtime->env();
    if (env == nullptr) return luaL_error(L, "app.list: no JNI environment");

    auto tasks = (jobjectArray) env->CallObjectMethod(runtime->hostObject(), runtime->host().listApps);
    if (tasks == nullptr) return luaL_error(L, "app.list: could not query running apps");

    const AppTaskFields &fields = runtime->appTaskFields();
    jsize count = env->GetArrayLength(tasks);
    lua_createtable(L, count, 0);
    for (jsize i = 0; i < count; i++) {
        jobject task = env->GetObjectArrayElement(tasks, i);
        auto jPackage = (jstring) env->GetObjectField(task, fields.packageName);
        const char *package = env->GetStringUTFChars(jPackage, nullptr);

        lua_createtable(L, 0, 2);
        lua_pushstring(L, package);
        lua_setfield(L, -2, "package");
        lua_pushinteger(L, env->GetIntField(task, fields.displayId));
        lua_setfield(L, -2, "display_id");
        lua_rawseti(L, -2, static_cast<lua_Integer>(i + 1));

        env->ReleaseStringUTFChars(jPackage, package);
        env->DeleteLocalRef(jPackage);
        env->DeleteLocalRef(task);
    }
    env->DeleteLocalRef(tasks);
    return 1;
}

int lua_device_notify(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *title = luaL_checkstring(L, 1);
    const char *text = luaL_optstring(L, 2, "");
    JNIEnv *env = runtime->env();
    if (env == nullptr) return 0;

    jstring jTitle = env->NewStringUTF(title);
    jstring jText = env->NewStringUTF(text);
    env->CallVoidMethod(runtime->hostObject(), runtime->host().notify, jTitle, jText);
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(jText);
    return 0;
}

int lua_device_open_uri(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *uri = luaL_checkstring(L, 1);
    JNIEnv *env = runtime->env();
    if (env == nullptr) return 0;

    jstring jUri = env->NewStringUTF(uri);
    env->CallVoidMethod(runtime->hostObject(), runtime->host().openUri, jUri);
    env->DeleteLocalRef(jUri);
    return 0;
}

int lua_data_set(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    const char *key = luaL_checkstring(L, 1);
    JNIEnv *env = runtime->env();
    if (env == nullptr) return 0;

    jstring jKey = env->NewStringUTF(key);
    jobject jValue = runtime->boxLuaValue(L, 2);
    env->CallVoidMethod(runtime->hostObject(), runtime->host().setData, jKey, jValue);
    env->DeleteLocalRef(jKey);
    if (jValue) env->DeleteLocalRef(jValue);
    return 0;
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
        {"read",     lua_vision_read},
        {"read_lines", lua_vision_read_lines},
        {"wait",     lua_vision_wait},
        {"wait_any", lua_vision_wait_any},
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
        {"back",        lua_input_back},
        {"home",        lua_input_home},
        {"recents",     lua_input_recents},
        {nullptr, nullptr},
};

const luaL_Reg kApp[] = {
        {"launch", lua_app_launch},
        {"list",   lua_app_list},
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
}

}  // namespace moonclicker
