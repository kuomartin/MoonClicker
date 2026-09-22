import assert from "node:assert/strict";
import { createServer, IncomingMessage, Server } from "node:http";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";
import {
  createDirectoryOnDevice,
  deleteFileOnDevice,
  mirrorDir,
  pullScriptToMirror,
  pushFileToDevice,
  removeMirroredFile,
  renameFileOnDevice,
  sha256Hex,
  syncChangedFileToMirror,
} from "../scriptMirror";

let server: Server;
let address: string;
let fileRequests: string[] = [];
let lastWriteBody: Buffer | undefined;
let deleteRequests: string[] = [];
let mkdirRequests: string[] = [];
let lastRenameBody: unknown;

const mainLuaContent = "log('hi')";
const mainLuaHash = sha256Hex(Buffer.from(mainLuaContent));

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

    if (url === "/scripts/hello/tree" && req.method === "GET") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify([
          { path: "main.lua", size: mainLuaContent.length, mtimeMs: 1, isDirectory: false, sha256: mainLuaHash },
          { path: "assets", size: 0, mtimeMs: 1, isDirectory: true, sha256: "" },
          { path: "assets/icon.png", size: 3, mtimeMs: 1, isDirectory: false, sha256: sha256Hex(Buffer.from("png")) },
        ]),
      );
      return;
    }
    if (url === "/scripts/hello/files/main.lua" && req.method === "GET") {
      fileRequests.push("main.lua");
      res.writeHead(200);
      res.end(mainLuaContent);
      return;
    }
    if (url === "/scripts/hello/files/assets/icon.png" && req.method === "GET") {
      fileRequests.push("assets/icon.png");
      res.writeHead(200);
      res.end("png");
      return;
    }
    if (url === "/scripts/hello/files/new.lua" && req.method === "PUT") {
      lastWriteBody = await readBody(req);
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/files/old.lua" && req.method === "DELETE") {
      deleteRequests.push("old.lua");
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/mkdir/newdir" && req.method === "POST") {
      mkdirRequests.push("newdir");
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/rename" && req.method === "POST") {
      lastRenameBody = JSON.parse((await readBody(req)).toString("utf8"));
      res.writeHead(200);
      res.end("OK");
      return;
    }
    if (url === "/scripts/hello/files/main.lua?echo" && req.method === "GET") {
      res.writeHead(200);
      res.end(mainLuaContent);
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

test("mirrorDir sanitizes the address into a safe path segment", () => {
  const dir = mirrorDir("/storage", "192.168.1.5:8787", "hello");
  assert.equal(dir, join("/storage", "mirrors", "192.168.1.5_8787", "hello"));
});

test("pullScriptToMirror downloads every file into the destination, creating subdirectories", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  fileRequests = [];
  try {
    await pullScriptToMirror(address, "hello", destDir);
    assert.equal(readFileSync(join(destDir, "main.lua"), "utf8"), mainLuaContent);
    assert.equal(readFileSync(join(destDir, "assets", "icon.png"), "utf8"), "png");
    assert.deepEqual(fileRequests.sort(), ["assets/icon.png", "main.lua"]);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("pullScriptToMirror skips downloading a file whose local content already matches the hash", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  fileRequests = [];
  try {
    mkdirSync(join(destDir, "assets"), { recursive: true });
    writeFileSync(join(destDir, "main.lua"), mainLuaContent);
    writeFileSync(join(destDir, "assets", "icon.png"), "stale");

    await pullScriptToMirror(address, "hello", destDir);

    assert.deepEqual(fileRequests, ["assets/icon.png"]);
    assert.equal(readFileSync(join(destDir, "assets", "icon.png"), "utf8"), "png");
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("pushFileToDevice PUTs the local file's current content", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    const localPath = join(destDir, "new.lua");
    writeFileSync(localPath, "return 42");

    await pushFileToDevice(address, "hello", destDir, localPath);

    assert.equal(lastWriteBody?.toString("utf8"), "return 42");
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("deleteFileOnDevice issues a DELETE for the file's relative path", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    await deleteFileOnDevice(address, "hello", destDir, join(destDir, "old.lua"));
    assert.deepEqual(deleteRequests, ["old.lua"]);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("createDirectoryOnDevice POSTs to mkdir for the folder's relative path", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    await createDirectoryOnDevice(address, "hello", destDir, join(destDir, "newdir"));
    assert.deepEqual(mkdirRequests, ["newdir"]);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("renameFileOnDevice sends relative from/to paths", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    await renameFileOnDevice(address, "hello", destDir, join(destDir, "a.lua"), join(destDir, "b.lua"));
    assert.deepEqual(lastRenameBody, { from: "a.lua", to: "b.lua", overwrite: true });
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("syncChangedFileToMirror writes the new content and reports it changed", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    const changed = await syncChangedFileToMirror(address, "hello", destDir, "main.lua");
    assert.equal(changed, true);
    assert.equal(readFileSync(join(destDir, "main.lua"), "utf8"), mainLuaContent);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("syncChangedFileToMirror is a no-op self-echo when local content already matches", async () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    writeFileSync(join(destDir, "main.lua"), mainLuaContent);
    const before = readFileSync(join(destDir, "main.lua"));

    const changed = await syncChangedFileToMirror(address, "hello", destDir, "main.lua");

    assert.equal(changed, false);
    assert.deepEqual(readFileSync(join(destDir, "main.lua")), before);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("removeMirroredFile deletes an existing local file and reports true", () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    writeFileSync(join(destDir, "gone.lua"), "x");
    assert.equal(removeMirroredFile(destDir, "gone.lua"), true);
    assert.equal(existsSync(join(destDir, "gone.lua")), false);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});

test("removeMirroredFile is a no-op and reports false when the file is already gone", () => {
  const destDir = mkdtempSync(join(tmpdir(), "moonclicker-mirror-"));
  try {
    assert.equal(removeMirroredFile(destDir, "never-existed.lua"), false);
  } finally {
    rmSync(destDir, { recursive: true, force: true });
  }
});
