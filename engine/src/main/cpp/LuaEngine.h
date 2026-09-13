#ifndef RELC_LUA_ENGINE_H
#define RELC_LUA_ENGINE_H

#include <string>

extern "C" {
#include "lua.h"
#include "lualib.h"
#include "lauxlib.h"
}

#include <android/log.h>

#define LOG_TAG "LuaEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 一個 `lua_State` 的 RAII 包裝：開標準函式庫與 cjson，然後就結束了。
 *
 * 載入與執行腳本刻意不在這裡——那是 ScriptRuntime 的事，它才知道停止旗標、
 * 事件回報與 on_stop 收尾該怎麼處理。
 */
class LuaEngine {
public:
    LuaEngine() = default;

    ~LuaEngine();

    bool init();

    lua_State *state() { return L; }

    void close();

private:
    lua_State *L = nullptr;
};

#endif // RELC_LUA_ENGINE_H
