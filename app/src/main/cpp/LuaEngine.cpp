#include "LuaEngine.h"

extern "C" {
int luaopen_cjson(lua_State *L);
}

LuaEngine::LuaEngine() : L(nullptr), coL(nullptr), coRef(LUA_NOREF) {}

LuaEngine::~LuaEngine() {
    stop();
}

bool LuaEngine::init() {
    L = luaL_newstate();
    if (!L) return false;

    luaL_openlibs(L);
    luaL_requiref(L, "cjson", luaopen_cjson, 0);
    lua_pop(L, 1);

    // Create match and input tables
    lua_newtable(L);
    lua_setglobal(L, "match");

    lua_newtable(L);
    lua_setglobal(L, "input");

    return true;
}

bool LuaEngine::loadFile(const std::string &filepath) {
    if (!L) return false;

    // Create a new thread (coroutine)
    coL = lua_newthread(L);
    coRef = luaL_ref(L, LUA_REGISTRYINDEX);

    int status = luaL_loadfile(coL, filepath.c_str());
    if (status != LUA_OK) {
        LOGE("Failed to load script file %s: %s", filepath.c_str(), lua_tostring(coL, -1));
        return false;
    }

    return true;
}

bool LuaEngine::resume() {
    if (!coL) return false;

    int nres;
    int status = lua_resume(coL, L, 0, &nres);

    if (status == LUA_OK) {
        LOGD("Script finished execution");
        return true;
    } else if (status == LUA_YIELD) {
        // Expected behavior for coroutines
        return true;
    } else {
        LOGE("Script execution error: %s", lua_tostring(coL, -1));
        return false;
    }
}

void LuaEngine::stop() {
    if (L) {
        if (coRef != LUA_NOREF) {
            luaL_unref(L, LUA_REGISTRYINDEX, coRef);
            coRef = LUA_NOREF;
        }
        lua_close(L);
        L = nullptr;
        coL = nullptr;
    }
}

void LuaEngine::registerFunction(const char *name, lua_CFunction func) {
    if (!L) return;
    lua_pushcfunction(L, func);
    lua_setglobal(L, name);
}
