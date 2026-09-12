#include "LuaEngine.h"

extern "C" {
int luaopen_cjson(lua_State *L);
}

LuaEngine::~LuaEngine() {
    close();
}

bool LuaEngine::init() {
    L = luaL_newstate();
    if (!L) return false;

    luaL_openlibs(L);
    luaL_requiref(L, "cjson", luaopen_cjson, 0);
    lua_pop(L, 1);

    return true;
}

void LuaEngine::close() {
    if (L) {
        lua_close(L);
        L = nullptr;
    }
}
