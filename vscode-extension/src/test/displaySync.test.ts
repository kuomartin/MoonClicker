import assert from "node:assert/strict";
import { createServer, Server } from "node:http";
import { after, before, test } from "node:test";
import { listDisplays, toggleDisplayMirror } from "../displaySync";

let server: Server;
let address: string;
let mirrorToggles: { id: string; enable: string | null }[] = [];

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
    /切換鏡像失敗（HTTP 500）/
  );
});
