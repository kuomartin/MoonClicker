import assert from "node:assert/strict";
import { test } from "node:test";
import { bindingNames, compareNames, stubNames } from "./check-lua-stub-sync.mjs";

const BINDINGS = `
const luaL_Reg kInput[] = {
        {"tap",   lua_input_tap},
        {"swipe", lua_input_swipe},
        {nullptr, nullptr},
};
void registerApi(lua_State *L, ScriptRuntime *runtime) {
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_log, 1);
    lua_setglobal(L, "log");
    lua_newtable(L);
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_screen_start_mirror, 1);
    lua_setfield(L, -2, "start_mirror");
    lua_newtable(L);
    lua_pushlightuserdata(L, runtime);
    lua_pushcclosure(L, lua_screen_index, 1);
    lua_setfield(L, -2, "__index");
    registerModule(L, runtime, "input", kInput);
}
static const char *kUi = R"lua(
local function helper() end
function ui.find(selector)
end
)lua";
`;

test("collects module, table and global function names", () => {
  assert.deepEqual([...bindingNames(BINDINGS)].sort(), ["input.swipe", "input.tap", "log", "screen.start_mirror", "ui.find"]);
});

test("stub names skip script callbacks", () => {
  const stub = "function log(...) end\nfunction on_stop() end\nfunction input.tap(x, y) end\n---@field x\n";
  assert.deepEqual([...stubNames(stub)].sort(), ["input.tap", "log"]);
});

test("passes when the names match", () => {
  assert.equal(compareNames(new Set(["a.b", "log"]), new Set(["log", "a.b"])).ok, true);
});

test("lists missing and extra names", () => {
  const result = compareNames(new Set(["input.tap", "input.key"]), new Set(["input.tap", "input.old"]));
  assert.equal(result.ok, false);
  assert.match(result.message, /缺少：input\.key/);
  assert.match(result.message, /多出.*input\.old/);
});
