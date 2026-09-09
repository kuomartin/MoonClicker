#ifndef RELC_LUA_ENGINE_H
#define RELC_LUA_ENGINE_H

#include <string>
#include <functional>

extern "C" {
#include "lua.h"
#include "lualib.h"
#include "lauxlib.h"
}

#include <android/log.h>

#define LOG_TAG "LuaEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class LuaEngine {
public:
    LuaEngine();
    ~LuaEngine();

    bool init();
    bool loadFile(const std::string& filepath);
    bool resume();
    void stop();

    lua_State* getLuaState() { return L; }
    lua_State* getCoroutineState() { return coL; }

    // API Registration
    void registerFunction(const char* name, lua_CFunction func);

private:
    lua_State* L;
    lua_State* coL;
    int coRef; // Reference to coroutine in registry
};

#endif // RELC_LUA_ENGINE_H
