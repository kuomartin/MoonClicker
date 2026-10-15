import assert from "node:assert/strict";
import { test } from "node:test";
import { mcpServerConfig } from "../mcp";

test("mcp config points at /mcp with the Workbench token", () => {
  assert.deepEqual(JSON.parse(mcpServerConfig("192.168.1.5:8787", "abc")), {
    mcpServers: {
      moonclicker: { type: "http", url: "http://192.168.1.5:8787/mcp", headers: { Authorization: "Bearer abc" } },
    },
  });
});

test("mcp config without a token has no headers", () => {
  assert.equal(JSON.parse(mcpServerConfig("127.0.0.1:8787", undefined)).mcpServers.moonclicker.headers, undefined);
});
