import assert from "node:assert/strict";
import { createServer, Server } from "node:http";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";
import AdmZip from "adm-zip";
import { listScripts, pullScript, pushScript, runScript } from "../scriptSync";

let server: Server;
let address: string;
let lastImportBody: Buffer | undefined;
let runRequests: string[] = [];

before(async () => {
  server = createServer((req, res) => {
    if (req.url === "/scripts" && req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify([{ id: "hello", name: "Hello" }]));
      return;
    }
    if (req.url === "/scripts/hello/export" && req.method === "GET") {
      const zip = new AdmZip();
      zip.addFile("main.lua", Buffer.from("log('hi')"));
      res.writeHead(200, { "content-type": "application/zip" });
      res.end(zip.toBuffer());
      return;
    }
    if (req.url === "/scripts/hello/import" && req.method === "PUT") {
      const chunks: Buffer[] = [];
      req.on("data", (chunk) => chunks.push(chunk));
      req.on("end", () => {
        lastImportBody = Buffer.concat(chunks);
        res.writeHead(200);
        res.end("OK");
      });
      return;
    }
    if (req.url === "/scripts/broken/import" && req.method === "PUT") {
      res.writeHead(400);
      res.end("壓縮檔裡找不到 main.lua");
      return;
    }
    if (req.url === "/scripts/hello/run" && req.method === "POST") {
      runRequests.push("hello");
      res.writeHead(202);
      res.end("Started");
      return;
    }
    if (req.url === "/scripts/running/run" && req.method === "POST") {
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

test("pullScript extracts the exported zip into the destination folder", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "relc-pull-"));
  try {
    await pullScript(address, "hello", destDir);
    assert.equal(readFileSync(join(destDir, "main.lua"), "utf8"), "log('hi')");
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("pushScript sends a zip of the source folder", async () => {
  const sourceDir = mkdtempSync(join(tmpdir(), "relc-push-"));
  try {
    writeFileSync(join(sourceDir, "main.lua"), "log('pushed')");
    await pushScript(address, "hello", sourceDir);

    assert.ok(lastImportBody);
    const zip = new AdmZip(lastImportBody);
    assert.equal(zip.readAsText("main.lua"), "log('pushed')");
  } finally {
    rmSync(sourceDir, { recursive: true, force: true });
  }
});

test("runScript triggers execution through the device's run route", async () => {
  await runScript(address, "hello");
  assert.deepEqual(runRequests, ["hello"]);
});

test("runScript throws with the server's reason when a script is already running", async () => {
  await assert.rejects(() => runScript(address, "running"), /already running/);
});

test("pushScript throws with the server's reason on failure", async () => {
  const sourceDir = mkdtempSync(join(tmpdir(), "relc-push-fail-"));
  try {
    await assert.rejects(
      () => pushScript(address, "broken", sourceDir),
      /找不到 main\.lua/,
    );
  } finally {
    rmSync(sourceDir, { recursive: true, force: true });
  }
});
