import assert from "node:assert/strict";
import { test } from "node:test";
import { mergeLuarc } from "../luarc";

test("adds the stub path to an empty config", () => {
  const result = mergeLuarc({}, "/ext/lua-meta");
  assert.deepEqual(result, { workspace: { library: ["/ext/lua-meta"] } });
});

test("appends to an existing library list without dropping other entries", () => {
  const result = mergeLuarc(
    { workspace: { library: ["/other/lib"], checkThirdParty: false } },
    "/ext/lua-meta",
  );
  assert.deepEqual(result, {
    workspace: { library: ["/other/lib", "/ext/lua-meta"], checkThirdParty: false },
  });
});

test("does not duplicate the stub path when it is already present", () => {
  const result = mergeLuarc({ workspace: { library: ["/ext/lua-meta"] } }, "/ext/lua-meta");
  assert.deepEqual(result, { workspace: { library: ["/ext/lua-meta"] } });
});

test("preserves top-level fields outside workspace", () => {
  const result = mergeLuarc({ runtime: { version: "Lua 5.4" } }, "/ext/lua-meta");
  assert.deepEqual(result, {
    runtime: { version: "Lua 5.4" },
    workspace: { library: ["/ext/lua-meta"] },
  });
});
