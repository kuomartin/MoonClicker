// CI guardrail（見 #60）：PR 改了 LuaBindings 卻沒改 LuaLS stub（#59）時擋下 PR。
// 這是「忘記改」的機械偵測（純比對檔案路徑），不驗證語意正確性。

const BINDING_PATHS = new Set([
  "engine/src/main/cpp/LuaBindings.h",
  "engine/src/main/cpp/LuaBindings.cpp",
]);

const STUB_PATHS = new Set(["vscode-extension/lua-meta/relc.lua"]);

/** @param {string[]} changedFiles */
export function checkLuaStubSync(changedFiles) {
  const bindingChanged = changedFiles.some((f) => BINDING_PATHS.has(f));
  const stubChanged = changedFiles.some((f) => STUB_PATHS.has(f));

  if (bindingChanged && !stubChanged) {
    return {
      ok: false,
      message:
        "LuaBindings.h/.cpp 有變動，但 vscode-extension/lua-meta/relc.lua（LuaLS stub，見 #59）沒有變動。" +
        "如果這次改動改變了 Lua API 表面，請同步更新 stub；如果沒有，仍需要碰一下 stub 檔案讓這個 check 通過。",
    };
  }
  return { ok: true };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { execFileSync } = await import("node:child_process");
  const [base, head] = process.argv.slice(2);
  if (!base || !head) {
    console.error("Usage: node check-lua-stub-sync.mjs <base-sha> <head-sha>");
    process.exit(2);
  }

  const diffOutput = execFileSync("git", ["diff", "--name-only", base, head], {
    encoding: "utf8",
  });
  const changedFiles = diffOutput.split("\n").filter(Boolean);

  const result = checkLuaStubSync(changedFiles);
  if (!result.ok) {
    console.error(result.message);
    process.exit(1);
  }
  console.log("OK: LuaBindings/stub sync check passed.");
}
