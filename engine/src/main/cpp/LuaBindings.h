#ifndef MOONCLICKER_LUA_BINDINGS_H
#define MOONCLICKER_LUA_BINDINGS_H

#include "LuaEngine.h"

class ScriptRuntime;

namespace moonclicker {

/** LUA_REGISTRYINDEX 上存放 ScriptRuntime* 的鍵。 */
constexpr const char *kRuntimeRegistryKey = "moonclicker.runtime";

/**
 * 註冊 MoonClicker Lua API v3 的完整命名空間：
 * `log` / `sleep` / `screen` / `vision` / `input` / `app` / `device` / `data`。
 *
 * 詳細語意見 docs/lua-api.md。
 */
void registerApi(lua_State *L, ScriptRuntime *runtime);

}  // namespace moonclicker

#endif // MOONCLICKER_LUA_BINDINGS_H
