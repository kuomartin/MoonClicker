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
 * 路徑相對腳本資料夾解析。
 */
VisionRequest readRequest(lua_State *L, int index, ScriptRuntime *runtime) {
    VisionRequest request;

    if (lua_isstring(L, index)) {
        request.imagePath = runtime->vision().resolvePath(lua_tostring(L, index));
        return request;
    }

    luaL_checktype(L, index, LUA_TTABLE);

    lua_getfield(L, index, "image");
    if (!lua_isstring(L, -1)) {
        lua_pop(L, 1);
        luaL_error(L, "vision request needs an `image` field (a path relative to the script)");
    }
    request.imagePath = runtime->vision().resolvePath(lua_tostring(L, -1));
    lua_pop(L, 1);

    lua_getfield(L, index, "threshold");
    if (lua_isnumber(L, -1)) request.threshold = lua_tonumber(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, index, "scale");
    if (lua_isnumber(L, -1)) {
        double scale = lua_tonumber(L, -1);
        if (scale > 0.0 && scale <= 1.0) request.scale = scale;
    }
    lua_pop(L, 1);

    lua_getfield(L, index, "gray");
    if (lua_isboolean(L, -1)) request.gray = lua_toboolean(L, -1);
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
}

/** 把這一輪的比對結果回報給 Kotlin，讓 Scripts UI 看得到最後一次比對發生了什麼。 */
void reportHits(ScriptRuntime *runtime, const std::vector<VisionRequest> &requests,
                const std::vector<VisionHit> &hits) {
    std::ostringstream out;
    out << '[';
    for (size_t i = 0; i < hits.size(); i++) {
        const VisionHit &hit = hits[i];
        if (i > 0) out << ',';
        out << "{\"name\":\"" << jsonEscape(requests[i].imagePath)
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
    if (!runtime->hasVision()) {
        luaL_error(L, "vision is unavailable on this target: the physical display produces no "
                      "frames, so only input.* works there. Run the script on a virtual display.");
    }
}

/**
 * find/wait 共用的核心：比對到第一個命中就停，或撐到 [timeoutMs] 為止。
 * @return 命中的 request 索引，沒有就回傳 -1。
 */
int matchUntil(lua_State *L, ScriptRuntime *runtime, const std::vector<VisionRequest> &requests,
               long timeoutMs) {
    long deadline = nowMs() + std::max(0L, timeoutMs);
    uint64_t seen = 0;

    while (true) {
        if (!runtime->isRunning()) raiseStopped(L);

        seen = runtime->vision().frameCounter();
        std::vector<VisionHit> hits = runtime->vision().match(requests);
        reportHits(runtime, requests, hits);

        for (size_t i = 0; i < hits.size(); i++) {
            if (hits[i].found) {
                pushHit(L, hits[i]);
                return static_cast<int>(i);
            }
        }

        long remaining = deadline - nowMs();
        if (remaining <= 0) return -1;

        // 等新影格；逾時就回到迴圈頂端重新檢查 deadline 與停止旗標。
        runtime->vision().waitForFrameAfter(seen, std::min(remaining, kWaitPollMs));
    }
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
    return 0;
}

int lua_sleep(lua_State *L) {
    auto ms = static_cast<long>(luaL_checkinteger(L, 1));
    if (!self(L)->interruptibleSleep(ms)) raiseStopped(L);
    return 0;
}

// --- screen ----------------------------------------------------------------

/** `screen` 的欄位是即時算的（旋轉會改變 width/height），所以走 __index 而不是固定值。 */
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
    } else {
        lua_pushnil(L);
    }
    return 1;
}

// --- vision ----------------------------------------------------------------

int lua_vision_find(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireVision(L, runtime);

    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    std::vector<VisionHit> hits = runtime->vision().match(requests);
    reportHits(runtime, requests, hits);

    if (hits[0].found) {
        pushHit(L, hits[0]);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

int lua_vision_wait(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireVision(L, runtime);

    std::vector<VisionRequest> requests{readRequest(L, 1, runtime)};
    auto timeout = static_cast<long>(luaL_optinteger(L, 2, kDefaultWaitTimeoutMs));

    if (matchUntil(L, runtime, requests, timeout) < 0) {
        lua_pushnil(L);
    }
    return 1;
}

int lua_vision_wait_any(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    requireVision(L, runtime);
    luaL_checktype(L, 1, LUA_TTABLE);

    std::vector<VisionRequest> requests;
    auto count = static_cast<int>(lua_rawlen(L, 1));
    for (int i = 1; i <= count; i++) {
        lua_rawgeti(L, 1, i);
        requests.push_back(readRequest(L, lua_gettop(L), runtime));
        lua_pop(L, 1);
    }
    if (requests.empty()) {
        luaL_error(L, "vision.wait_any needs at least one request");
    }

    auto timeout = static_cast<long>(luaL_optinteger(L, 2, kDefaultWaitTimeoutMs));

    int index = matchUntil(L, runtime, requests, timeout);
    if (index < 0) {
        lua_pushnil(L);
        return 1;
    }
    // matchUntil 已經把命中的 table 推上堆疊；索引放前面，回傳 index, result。
    lua_pushinteger(L, index + 1);
    lua_insert(L, -2);
    return 2;
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
    std::vector<int> points = readPoints(L, 1);
    auto duration = static_cast<long>(luaL_optinteger(L, 2, kDefaultSwipeDurationMs));

    callSwipe(runtime, -1, points, duration, false);
    if (!runtime->interruptibleSleep(duration)) raiseStopped(L);
    return 0;
}

int lua_input_multi_swipe(lua_State *L) {
    ScriptRuntime *runtime = self(L);
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
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    auto x = static_cast<int>(luaL_checknumber(L, 2));
    auto y = static_cast<int>(luaL_checknumber(L, 3));
    callSwipe(self(L), id, {x, y}, 0, true);
    return 0;
}

int lua_input_move(lua_State *L) {
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    auto x = static_cast<int>(luaL_checknumber(L, 2));
    auto y = static_cast<int>(luaL_checknumber(L, 3));
    callSwipe(self(L), id, {x, y}, 0, true);
    return 0;
}

int lua_input_up(lua_State *L) {
    ScriptRuntime *runtime = self(L);
    auto id = static_cast<int>(luaL_checkinteger(L, 1));
    JNIEnv *env = runtime->env();
    if (env) env->CallBooleanMethod(runtime->hostObject(), runtime->host().pointerUp, id);
    return 0;
}

bool callKey(ScriptRuntime *runtime, int keyCode) {
    JNIEnv *env = runtime->env();
    if (env == nullptr) return false;
    return env->CallBooleanMethod(runtime->hostObject(), runtime->host().key, keyCode);
}

int lua_input_key(lua_State *L) {
    lua_pushboolean(L, callKey(self(L), static_cast<int>(luaL_checkinteger(L, 1))));
    return 1;
}

int lua_input_back(lua_State *L) {
    lua_pushboolean(L, callKey(self(L), AKEYCODE_BACK));
    return 1;
}

int lua_input_home(lua_State *L) {
    lua_pushboolean(L, callKey(self(L), AKEYCODE_HOME));
    return 1;
}

int lua_input_recents(lua_State *L) {
    lua_pushboolean(L, callKey(self(L), AKEYCODE_APP_SWITCH));
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

namespace relc {

void registerApi(lua_State *L, ScriptRuntime *runtime) {
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_log, 1);
    lua_setglobal(L, "log");

    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_sleep, 1);
    lua_setglobal(L, "sleep");

    // screen 是空表 + __index，欄位每次讀都重新計算（旋轉會改變 width/height）。
    lua_newtable(L);
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

}  // namespace relc
