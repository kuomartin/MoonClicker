// CI 檢查：Lua API 的函式名稱與 LuaLS stub（vscode-extension/lua-meta/moonclicker.lua）一致。
// 只比對「模組.函式」與全域函式的名稱，參數與回傳型別不在檢查範圍。

import { existsSync, readFileSync } from "node:fs";

export const BINDING_SOURCES = ["engine/src/main/cpp/LuaBindings.cpp", "engine/src/main/cpp/UiModule.h"];
export const STUB_PATH = "vscode-extension/lua-meta/moonclicker.lua";

/** 腳本自己定義、引擎呼叫的回呼，只會出現在 stub。 */
const CALLBACKS = new Set(["on_stop"]);

/**
 * C++ 綁定註冊的名稱：
 * - `luaL_Reg kX[] = {{"name", fn}, ...}` 配上 `registerModule(L, runtime, "mod", kX)`
 * - `lua_pushcclosure(L, lua_x, 1); lua_setglobal(L, "x")` 的全域函式
 * - `lua_pushcclosure(L, lua_mod_name, 1); lua_setfield(L, -2, "name")` 掛在表上的函式
 * - 內嵌 Lua 裡的 `function mod.name(`
 * @param {string} source
 */
export function bindingNames(source) {
  const names = new Set();
  const tables = new Map();
  for (const m of source.matchAll(/luaL_Reg\s+(\w+)\[\]\s*=\s*\{([\s\S]*?)\};/g)) {
    tables.set(m[1], [...m[2].matchAll(/\{\s*"(\w+)"\s*,/g)].map((e) => e[1]));
  }
  for (const m of source.matchAll(/registerModule\(\s*L\s*,\s*\w+\s*,\s*"(\w+)"\s*,\s*(\w+)\s*\)/g)) {
    for (const fn of tables.get(m[2]) ?? []) names.add(`${m[1]}.${fn}`);
  }
  for (const m of source.matchAll(/lua_pushcclosure\(L,\s*(lua_\w+)\s*,[^;]*;\s*lua_set(global|field)\(L,\s*(?:-2,\s*)?"(\w+)"\)/g)) {
    const [, cName, kind, name] = m;
    if (kind === "global") {
      names.add(name);
    } else if (!name.startsWith("__") && cName.endsWith(`_${name}`)) {
      names.add(`${cName.slice("lua_".length, -name.length - 1)}.${name}`);
    }
  }
  for (const m of source.matchAll(/^\s*function\s+([a-z_]\w*\.[a-z_]\w*)\s*\(/gm)) names.add(m[1]);
  return names;
}

/** @param {string} stub */
export function stubNames(stub) {
  const names = new Set();
  for (const m of stub.matchAll(/^function\s+([\w.]+)\s*\(/gm)) {
    if (!CALLBACKS.has(m[1])) names.add(m[1]);
  }
  return names;
}

/**
 * @param {Set<string>} bindings
 * @param {Set<string>} stub
 */
export function compareNames(bindings, stub) {
  const missing = [...bindings].filter((n) => !stub.has(n)).sort();
  const extra = [...stub].filter((n) => !bindings.has(n)).sort();
  if (missing.length === 0 && extra.length === 0) return { ok: true };
  const lines = [`Lua API 與 ${STUB_PATH} 不一致：`];
  if (missing.length) lines.push(`  stub 缺少：${missing.join(", ")}`);
  if (extra.length) lines.push(`  stub 多出（綁定裡沒有）：${extra.join(", ")}`);
  return { ok: false, message: lines.join("\n") };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const bindings = new Set(
    BINDING_SOURCES.filter((p) => existsSync(p)).flatMap((p) => [...bindingNames(readFileSync(p, "utf8"))]),
  );
  const result = compareNames(bindings, stubNames(readFileSync(STUB_PATH, "utf8")));
  if (!result.ok) {
    console.error(result.message);
    process.exit(1);
  }
  console.log(`OK: ${bindings.size} Lua API names match the stub.`);
}
