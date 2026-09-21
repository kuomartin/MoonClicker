import assert from "node:assert/strict";
import { createServer, IncomingMessage, Server } from "node:http";
import { after, before, test } from "node:test";
import {
  deleteEntry,
  getTree,
  listScripts,
  mkdir,
  readFile,
  renameEntry,
  runScript,
  ScriptHttpError,
  writeFile,
} from "../scriptSync";

let server: Server;
let address: string;
let lastWriteBody: Buffer | undefined;
let lastWritePath: string | undefined;
let lastRenameBody: unknown;
let deleteRequests: string[] = [];
let mkdirRequests: string[] = [];
let runRequests: string[] = [];

function readBody(req: IncomingMessage): Promise<Buffer> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
  });
}

before(async () => {
  server = createServer(async (req, res) => {
    const url = req.url ?? "";

    if (url === "/scripts" && req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify([{ id: "hello", name: "Hello" }]));
      return;
    }
    if (url === "/scripts/hello/tree" && req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify([{ path: "main.lua", size: 9, mtimeMs: 123, isDirectory: false }]));
      return;
    }
    if (url === "/scripts/hello/files/main.lua" && req.method === "GET") {
      res.writeHead(200);
      res.end("log('hi')");
      return;
    }
    if (url === "/scripts/hello/files/missing.lua" && req.method === "GET") {
      res.writeHead(404);
      res.end("File not found");
      return;
    }
    if (url === "/scripts/hello/files/lib/util.lua" && req.method === "PUT") {
      lastWritePath = "lib/util.lua";
      lastWriteBody = await readBody(req);
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/files/old.png" && req.method === "DELETE") {
      deleteRequests.push("old.png");
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/files/missing.png" && req.method === "DELETE") {
      res.writeHead(404);
      res.end("File not found");
      return;
    }
    if (url === "/scripts/hello/mkdir/assets" && req.method === "POST") {
      mkdirRequests.push("assets");
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/rename" && req.method === "POST") {
      const body = JSON.parse((await readBody(req)).toString("utf8"));
      lastRenameBody = body;
      if (body.to === "taken.lua") {
        res.writeHead(409);
        res.end("Destination already exists");
        return;
      }
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/run" && req.method === "POST") {
      runRequests.push("hello");
      res.writeHead(202);
      res.end("Started");
      return;
    }
    if (url === "/scripts/running/run" && req.method === "POST") {
      res.writeHead(409);
      res.end("A script is already running");
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

test("listScripts returns the device's script summaries", async () => {
  const scripts = await listScripts(address);
  assert.deepEqual(scripts, [{ id: "hello", name: "Hello" }]);
});

test("getTree returns the recursive manifest", async () => {
  const tree = await getTree(address, "hello");
  assert.deepEqual(tree, [{ path: "main.lua", size: 9, mtimeMs: 123, isDirectory: false }]);
});

test("readFile returns the raw bytes of a single file", async () => {
  const bytes = await readFile(address, "hello", "main.lua");
  assert.equal(Buffer.from(bytes).toString("utf8"), "log('hi')");
});

test("readFile throws a ScriptHttpError carrying the status code on 404", async () => {
  await assert.rejects(
    () => readFile(address, "hello", "missing.lua"),
    (err: unknown) => err instanceof ScriptHttpError && err.status === 404,
  );
});

test("writeFile PUTs the bytes to the encoded nested path", async () => {
  await writeFile(address, "hello", "lib/util.lua", new TextEncoder().encode("return 1"));
  assert.equal(lastWritePath, "lib/util.lua");
  assert.equal(lastWriteBody?.toString("utf8"), "return 1");
});

test("deleteEntry issues a DELETE for the given path", async () => {
  await deleteEntry(address, "hello", "old.png");
  assert.deepEqual(deleteRequests, ["old.png"]);
});

test("deleteEntry throws a ScriptHttpError on 404", async () => {
  await assert.rejects(
    () => deleteEntry(address, "hello", "missing.png"),
    (err: unknown) => err instanceof ScriptHttpError && err.status === 404,
  );
});

test("mkdir POSTs to the mkdir route for the given path", async () => {
  await mkdir(address, "hello", "assets");
  assert.deepEqual(mkdirRequests, ["assets"]);
});

test("renameEntry sends from/to/overwrite as JSON", async () => {
  await renameEntry(address, "hello", "old.lua", "new.lua", true);
  assert.deepEqual(lastRenameBody, { from: "old.lua", to: "new.lua", overwrite: true });
});

test("renameEntry throws a ScriptHttpError with the server's reason on conflict", async () => {
  await assert.rejects(
    () => renameEntry(address, "hello", "old.lua", "taken.lua", false),
    (err: unknown) =>
      err instanceof ScriptHttpError && err.status === 409 && /already exists/.test(err.message),
  );
});

test("runScript triggers execution through the device's run route", async () => {
  await runScript(address, "hello");
  assert.deepEqual(runRequests, ["hello"]);
});

test("runScript throws with the server's reason when a script is already running", async () => {
  await assert.rejects(() => runScript(address, "running"), /already running/);
});
