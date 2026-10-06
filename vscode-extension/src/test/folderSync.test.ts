import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, IncomingMessage, Server } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, beforeEach, test } from "node:test";
import { FolderSync, readSyncState } from "../folderSync";

/** 裝置端 `scripts/` 的記憶體版本：腳本 id → 相對路徑 → 內容。 */
const device = new Map<string, Map<string, Buffer>>();
let server: Server;
let address: string;
let root: string;

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
  });
}

const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");

before(async () => {
  server = createServer(async (req, res) => {
    const url = decodeURIComponent(req.url ?? "");
    const send = (status: number, body: string | Buffer = "") => {
      res.writeHead(status);
      res.end(body);
    };
    if (url === "/scripts" && req.method === "GET") {
      const list = [...device.entries()].filter(([, f]) => f.has("main.lua")).map(([id]) => ({ id, name: id }));
      return send(200, JSON.stringify(list));
    }
    let m = url.match(/^\/scripts\/([^/]+)$/);
    if (m) {
      const id = m[1];
      if (req.method === "POST") {
        if (device.has(id)) return send(409, "exists");
        device.set(id, new Map([["main.lua", Buffer.from(`-- ${id}\n`)], ["script.json", Buffer.from(`{"uniqueId":"${id}"}`)]]));
        return send(201);
      }
      if (req.method === "DELETE") return send(device.delete(id) ? 200 : 404);
    }
    m = url.match(/^\/scripts\/([^/]+)\/tree$/);
    if (m) {
      const files = device.get(m[1]);
      if (!files) return send(404);
      const entries = [...files.entries()].map(([p, c]) => ({ path: p, size: c.length, mtimeMs: 0, isDirectory: false, sha256: sha(c) }));
      return send(200, JSON.stringify(entries));
    }
    m = url.match(/^\/scripts\/([^/]+)\/files\/(.+)$/);
    if (m) {
      const files = device.get(m[1]);
      if (!files) return send(404);
      const p = m[2];
      if (req.method === "GET") return files.has(p) ? send(200, files.get(p)!) : send(404);
      if (req.method === "PUT") {
        files.set(p, await readBody(req));
        return send(200);
      }
      if (req.method === "DELETE") return send(files.delete(p) ? 200 : 404);
    }
    send(404);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  address = `127.0.0.1:${port}`;
});

after(() => server.close());

beforeEach(() => {
  device.clear();
  if (root) rmSync(root, { recursive: true, force: true });
  root = mkdtempSync(join(tmpdir(), "moonclicker-sync-"));
});

function deviceScript(id: string, files: Record<string, string>) {
  device.set(id, new Map(Object.entries(files).map(([p, c]) => [p, Buffer.from(c)])));
}

function localFile(key: string, content: string) {
  const target = join(root, ...key.split("/"));
  mkdirSync(join(target, ".."), { recursive: true });
  writeFileSync(target, content);
}

const readLocal = (key: string) => readFileSync(join(root, ...key.split("/")), "utf8");
const deviceFile = (key: string) => device.get(key.split("/")[0])?.get(key.split("/").slice(1).join("/"))?.toString("utf8");

function newSync(confirm = true) {
  return new FolderSync({ root, deviceId: "dev-1", address, confirmDeleteScripts: async () => confirm });
}

test("first sync pulls every script and records the device", async () => {
  deviceScript("demo", { "main.lua": "log(1)", "img/btn.png": "png" });
  const report = await newSync().fullSync();
  assert.deepEqual(report.pulled.sort(), ["demo/img/btn.png", "demo/main.lua"]);
  assert.equal(readLocal("demo/img/btn.png"), "png");
  assert.equal(readSyncState(root)?.deviceId, "dev-1");
});

test("a folder synced with another device is refused", async () => {
  await newSync().fullSync();
  assert.throws(() => new FolderSync({ root, deviceId: "dev-2", address, confirmDeleteScripts: async () => true }));
});

test("changes on one side flow to the other on the next sync", async () => {
  deviceScript("demo", { "main.lua": "v1", "lib.lua": "l1" });
  await newSync().fullSync();
  localFile("demo/main.lua", "v2-local");
  device.get("demo")!.set("lib.lua", Buffer.from("l2-device"));

  const report = await newSync().fullSync();

  assert.deepEqual(report.pushed, ["demo/main.lua"]);
  assert.deepEqual(report.pulled, ["demo/lib.lua"]);
  assert.equal(deviceFile("demo/main.lua"), "v2-local");
  assert.equal(readLocal("demo/lib.lua"), "l2-device");
});

test("a conflict keeps the local file, saves the device copy, and lets local win afterwards", async () => {
  deviceScript("demo", { "main.lua": "v1" });
  await newSync().fullSync();
  localFile("demo/main.lua", "local");
  device.get("demo")!.set("main.lua", Buffer.from("device"));

  const report = await newSync().fullSync();
  assert.deepEqual(report.conflicts, ["demo/main.lua"]);
  assert.equal(readLocal("demo/main.lua"), "local");
  assert.equal(readLocal("demo/main.lua.device"), "device");
  assert.equal(deviceFile("demo/main.lua.device"), undefined);

  const next = await newSync().fullSync();
  assert.deepEqual(next.pushed, ["demo/main.lua"]);
  assert.equal(deviceFile("demo/main.lua"), "local");
});

test("a new local script with main.lua is created on the device", async () => {
  localFile("fresh/main.lua", "log('new')");
  localFile("fresh/a.png", "png");
  const report = await newSync().fullSync();
  assert.deepEqual(report.pushed.sort(), ["fresh/a.png", "fresh/main.lua"]);
  assert.equal(deviceFile("fresh/main.lua"), "log('new')");
});

test("new local scripts with an invalid name or no main.lua are skipped", async () => {
  localFile("Bad Name/main.lua", "x");
  localFile("nomain/readme.txt", "x");
  const report = await newSync().fullSync();
  assert.deepEqual(report.skipped.map((s) => s.scriptId).sort(), ["Bad Name", "nomain"]);
  assert.equal(device.size, 0);
});

test("deleting a local script folder deletes it on the device after confirmation", async () => {
  deviceScript("demo", { "main.lua": "v1" });
  await newSync().fullSync();
  rmSync(join(root, "demo"), { recursive: true });

  const report = await newSync(true).fullSync();

  assert.equal(device.has("demo"), false);
  assert.deepEqual(report.deletedRemote, ["demo/main.lua"]);
});

test("declining the deletion keeps the device script and brings it back locally", async () => {
  deviceScript("demo", { "main.lua": "v1" });
  await newSync().fullSync();
  rmSync(join(root, "demo"), { recursive: true });

  const report = await newSync(false).fullSync();
  assert.equal(device.has("demo"), true);
  assert.deepEqual(report.skipped.map((s) => s.scriptId), ["demo"]);

  await newSync(false).fullSync();
  assert.equal(readLocal("demo/main.lua"), "v1");
});

test("a script deleted on the device disappears locally", async () => {
  deviceScript("demo", { "main.lua": "v1" });
  await newSync().fullSync();
  device.delete("demo");

  await newSync().fullSync();

  assert.equal(existsSync(join(root, "demo")), false);
});

test("ignored files are not pushed", async () => {
  localFile("demo/main.lua", "x");
  localFile("demo/.moonclickerignore", "*.psd\n");
  localFile("demo/art.psd", "big");
  await newSync().fullSync();
  assert.equal(deviceFile("demo/main.lua"), "x");
  assert.equal(deviceFile("demo/art.psd"), undefined);
  assert.equal(deviceFile("demo/.moonclickerignore"), undefined);
});

test("live: a saved file is pushed and its echo from the device changes nothing", async () => {
  deviceScript("demo", { "main.lua": "v1" });
  const sync = newSync();
  await sync.fullSync();
  localFile("demo/main.lua", "v2");

  const pushed = await sync.localChanged(join(root, "demo", "main.lua"));
  const echo = await sync.remoteChanged("demo", "main.lua", "changed");

  assert.deepEqual(pushed.pushed, ["demo/main.lua"]);
  assert.deepEqual(echo.pulled, []);
  assert.deepEqual(echo.conflicts, []);
  assert.equal(deviceFile("demo/main.lua"), "v2");
});

test("live: a device change is written locally unless the local file was edited", async () => {
  deviceScript("demo", { "main.lua": "v1", "lib.lua": "l1" });
  const sync = newSync();
  await sync.fullSync();
  device.get("demo")!.set("main.lua", Buffer.from("device"));
  device.get("demo")!.set("lib.lua", Buffer.from("device-lib"));
  localFile("demo/lib.lua", "local-lib");

  const a = await sync.remoteChanged("demo", "main.lua", "changed");
  const b = await sync.remoteChanged("demo", "lib.lua", "changed");

  assert.deepEqual(a.pulled, ["demo/main.lua"]);
  assert.equal(readLocal("demo/main.lua"), "device");
  assert.deepEqual(b.conflicts, ["demo/lib.lua"]);
  assert.equal(readLocal("demo/lib.lua"), "local-lib");
  assert.equal(readLocal("demo/lib.lua.device"), "device-lib");
});

test("live: deleting a file locally deletes it on the device", async () => {
  deviceScript("demo", { "main.lua": "v1", "old.lua": "o" });
  const sync = newSync();
  await sync.fullSync();
  rmSync(join(root, "demo", "old.lua"));

  await sync.localDeleted(join(root, "demo", "old.lua"));

  assert.equal(deviceFile("demo/old.lua"), undefined);
});

test("live: a new local script is created once main.lua exists", async () => {
  const sync = newSync();
  await sync.fullSync();
  localFile("fresh/a.png", "png");
  await sync.localChanged(join(root, "fresh", "a.png"));
  assert.equal(device.has("fresh"), false);

  localFile("fresh/main.lua", "log(1)");
  await sync.localChanged(join(root, "fresh", "main.lua"));

  assert.equal(deviceFile("fresh/main.lua"), "log(1)");
  assert.equal(deviceFile("fresh/a.png"), "png");
});

test("paths outside script folders are not synced", () => {
  const sync = newSync();
  assert.equal(sync.keyFor(join(root, ".moonclicker", "state.json")), undefined);
  assert.equal(sync.keyFor(join(root, "..", "elsewhere.lua")), undefined);
  assert.equal(sync.keyFor(join(root, "demo", "img", "a.png")), "demo/img/a.png");
});
