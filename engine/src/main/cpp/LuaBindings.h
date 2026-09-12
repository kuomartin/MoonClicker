#ifndef RELC_LUA_BINDINGS_H
#define RELC_LUA_BINDINGS_H

#include "LuaEngine.h"

class ScriptRuntime;

namespace relc {

/** LUA_REGISTRYINDEX 上存放 ScriptRuntime* 的鍵。 */
constexpr const char *kRuntimeRegistryKey = "relc.runtime";

/**
 * 註冊 ReLC Lua API v3 的完整命名空間：
 * `log` / `sleep` / `screen` / `vision` / `input` / `app` / `device` / `data`。
 *
 * 詳細語意見 docs/lua-api.md。
 */
void registerApi(lua_State *L, ScriptRuntime *runtime);

}  // namespace relc

#endif // RELC_LUA_BINDINGS_H
