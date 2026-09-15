import assert from "node:assert/strict";
import { test } from "node:test";
import { checkLuaStubSync } from "./check-lua-stub-sync.mjs";

test("fails when LuaBindings.cpp changed but the stub did not", () => {
  const result = checkLuaStubSync(["engine/src/main/cpp/LuaBindings.cpp"]);
  assert.equal(result.ok, false);
});

test("fails when LuaBindings.h changed but the stub did not", () => {
  const result = checkLuaStubSync(["engine/src/main/cpp/LuaBindings.h"]);
  assert.equal(result.ok, false);
});

test("passes when both the bindings and the stub changed", () => {
  const result = checkLuaStubSync([
    "engine/src/main/cpp/LuaBindings.cpp",
    "vscode-extension/lua-meta/relc.lua",
  ]);
  assert.equal(result.ok, true);
});

test("passes when neither changed", () => {
  const result = checkLuaStubSync(["app/src/main/java/com/xaxaxax/relc/RelcActivity.kt"]);
  assert.equal(result.ok, true);
});

test("passes when only the stub changed", () => {
  const result = checkLuaStubSync(["vscode-extension/lua-meta/relc.lua"]);
  assert.equal(result.ok, true);
});
