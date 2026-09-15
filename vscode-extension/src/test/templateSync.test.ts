import assert from "node:assert/strict";
import { createServer, Server } from "node:http";
import { after, before, test } from "node:test";
import {
  checkTemplateNameConflict,
  normalizeTemplateName,
  saveTemplate,
  type TemplateRoi,
} from "../templateSync";

let server: Server;
let address: string;
let lastPutUrl: string | undefined;
let lastPutBody: Buffer | undefined;

before(async () => {
  server = createServer((req, res) => {
    if (req.method === "PUT" && req.url?.startsWith("/scripts/hello/templates/btn_ok")) {
      lastPutUrl = req.url;
      const chunks: Buffer[] = [];
      req.on("data", (chunk) => chunks.push(chunk));
      req.on("end", () => {
        lastPutBody = Buffer.concat(chunks);
        res.writeHead(200);
        res.end("OK");
      });
      return;
    }
    if (req.method === "PUT" && req.url?.startsWith("/scripts/hello/templates/btn_conflict")) {
      res.writeHead(409);
      res.end("Template already exists: btn_conflict");
      return;
    }
    if (req.method === "PUT" && req.url?.startsWith("/scripts/not_found/templates/btn")) {
      res.writeHead(404);
      res.end("Script not found");
      return;
    }
    if (req.method === "PUT" && req.url?.startsWith("/scripts/hello/templates/bad_roi")) {
      res.writeHead(400);
      res.end("Missing or invalid roi (expects integer x, y, w, h query params)");
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

test("saveTemplate sends PUT request with roi query params and PNG body", async () => {
  const roi: TemplateRoi = { x: 10, y: 20, w: 100, h: 50 };
  const fakePng = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3]);

  await saveTemplate(address, "hello", "btn_ok", roi, fakePng);

  assert.equal(
    lastPutUrl,
    "/scripts/hello/templates/btn_ok?x=10&y=20&w=100&h=50",
  );
  assert.ok(lastPutBody);
  assert.deepEqual(new Uint8Array(lastPutBody), fakePng);
});

test("saveTemplate throws on 409 conflict with server reason", async () => {
  const roi: TemplateRoi = { x: 0, y: 0, w: 10, h: 10 };
  const fakePng = new Uint8Array([1, 2, 3]);

  await assert.rejects(
    () => saveTemplate(address, "hello", "btn_conflict", roi, fakePng),
    /Template already exists: btn_conflict/,
  );
});

test("saveTemplate throws on 404 script not found", async () => {
  const roi: TemplateRoi = { x: 0, y: 0, w: 10, h: 10 };
  const fakePng = new Uint8Array([1, 2, 3]);

  await assert.rejects(
    () => saveTemplate(address, "not_found", "btn", roi, fakePng),
    /Script not found/,
  );
});

test("saveTemplate rejects invalid template name before network call", async () => {
  const roi: TemplateRoi = { x: 0, y: 0, w: 10, h: 10 };
  const fakePng = new Uint8Array([1, 2, 3]);

  await assert.rejects(
    () => saveTemplate(address, "hello", "foo/bar", roi, fakePng),
    /模板名稱不可包含/,
  );
  await assert.rejects(
    () => saveTemplate(address, "hello", "..", roi, fakePng),
    /模板名稱不可包含/,
  );
  await assert.rejects(
    () => saveTemplate(address, "hello", "   ", roi, fakePng),
    /模板名稱不可為空/,
  );
});

test("normalizeTemplateName trims whitespace and strips optional .png extension", () => {
  assert.equal(normalizeTemplateName("btn_ok"), "btn_ok");
  assert.equal(normalizeTemplateName("  btn_ok.png  "), "btn_ok");
  assert.equal(normalizeTemplateName("my.template.PNG"), "my.template");
});

test("checkTemplateNameConflict detects matching template names regardless of .png extension", () => {
  const existing = ["btn_ok", "btn_cancel.png", "icon_close"];
  assert.equal(checkTemplateNameConflict(existing, "btn_ok"), true);
  assert.equal(checkTemplateNameConflict(existing, "btn_ok.png"), true);
  assert.equal(checkTemplateNameConflict(existing, "btn_cancel"), true);
  assert.equal(checkTemplateNameConflict(existing, "BTN_OK"), true);
  assert.equal(checkTemplateNameConflict(existing, "btn_new"), false);
});
