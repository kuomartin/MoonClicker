import assert from "node:assert/strict";
import { createServer, Server } from "node:http";
import { after, before, test } from "node:test";
import { injectInput, listDisplays, toggleDisplayMirror } from "../displaySync";

let server: Server;
let address: string;
let mirrorToggles: { id: string; enable: string | null }[] = [];
let inputs: { id: string; body: unknown }[] = [];

before(async () => {
  server = createServer((req, res) => {
    const url = new URL(req.url || "/", `http://${req.headers.host}`);
    if (url.pathname === "/displays" && req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify([
          { id: 0, name: "Physical Display", width: 1080, height: 2400, isVirtual: false, isMirrorActive: false },
          { id: 8, name: "Virtual Display 8", width: 2400, height: 1080, isVirtual: true, isMirrorActive: true },
        ])
      );
      return;
    }
    const mirrorMatch = url.pathname.match(/^\/displays\/(\d+)\/mirror$/);
    if (mirrorMatch && req.method === "POST") {
      const id = mirrorMatch[1];
      const enable = url.searchParams.get("enable");
      mirrorToggles.push({ id, enable });
      if (id === "999") {
        res.writeHead(500);
        res.end("Failed to update mirror");
        return;
      }
      res.writeHead(200);
      res.end("OK");
      return;
    }
    const inputMatch = url.pathname.match(/^\/displays\/(\d+)\/input$/);
    if (inputMatch && req.method === "POST") {
      const chunks: Buffer[] = [];
      req.on("data", (c) => chunks.push(c));
      req.on("end", () => {
        inputs.push({ id: inputMatch[1], body: JSON.parse(Buffer.concat(chunks).toString()) });
        res.writeHead(inputMatch[1] === "999" ? 503 : 200);
        res.end(inputMatch[1] === "999" ? "Failed to inject input" : "OK");
      });
      return;
    }
    res.writeHead(404);
    res.end();
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  address = `127.0.0.1:${port}`;
});

after(() => {
  server.close();
});

test("listDisplays returns device display list with isMirrorActive and isVirtual", async () => {
  const displays = await listDisplays(address);
  assert.equal(displays.length, 2);
  assert.deepEqual(displays[0], {
    id: 0,
    name: "Physical Display",
    width: 1080,
    height: 2400,
    isVirtual: false,
    isMirrorActive: false,
  });
  assert.equal(displays[1].isVirtual, true);
  assert.equal(displays[1].isMirrorActive, true);
});

test("toggleDisplayMirror issues POST with enable query param", async () => {
  mirrorToggles = [];
  await toggleDisplayMirror(address, 0, true);
  assert.equal(mirrorToggles.length, 1);
  assert.deepEqual(mirrorToggles[0], { id: "0", enable: "true" });

  await toggleDisplayMirror(address, 0, false);
  assert.equal(mirrorToggles.length, 2);
  assert.deepEqual(mirrorToggles[1], { id: "0", enable: "false" });
});

test("toggleDisplayMirror throws on server error", async () => {
  await assert.rejects(
    () => toggleDisplayMirror(address, 999, true),
    /Failed to toggle mirroring \(HTTP 500\)/
  );
});

test("injectInput posts the gesture in frame proportions", async () => {
  inputs = [];
  await injectInput(address, 8, [[0.1, 0.2], [0.9, 0.2]], 300);
  assert.deepEqual(inputs, [{ id: "8", body: { points: [[0.1, 0.2], [0.9, 0.2]], durationMs: 300 } }]);
});

test("injectInput surfaces the device's reason", async () => {
  await assert.rejects(() => injectInput(address, 999, [[0.5, 0.5]], 50), /Failed to inject input/);
});
