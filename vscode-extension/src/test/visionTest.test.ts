import assert from "node:assert/strict";
import { test } from "node:test";
import { ocrTestBody } from "../visionTest";

test("ocrTestBody sends only what each mode uses", () => {
  const roi = { x: 1, y: 2, w: 3, h: 4 };

  assert.deepEqual(ocrTestBody({ displayId: 7, mode: "read", roi, text: "ignored", threshold: 0.9 }), {
    displayId: 7,
    mode: "read",
    roi,
  });
  assert.deepEqual(ocrTestBody({ displayId: 7, mode: "read_lines", roi: null, intervalMs: 800 }), {
    displayId: 7,
    mode: "read_lines",
    intervalMs: 800,
  });
  assert.deepEqual(ocrTestBody({ displayId: 7, mode: "find", text: "開始", exact: true, threshold: 0.9 }), {
    displayId: 7,
    mode: "find",
    text: "開始",
    exact: true,
    threshold: 0.9,
  });
});

test("startOcrTest turns a 412 into a pointer to the device's OCR settings", async () => {
  const { createServer } = await import("node:http");
  const { startOcrTest } = await import("../visionTest");
  const server = createServer((_req, res) => {
    res.statusCode = 412;
    res.end("OCR is not installed");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as { port: number };
  try {
    await assert.rejects(startOcrTest(`127.0.0.1:${port}`, { displayId: 7, mode: "read_lines" }), /文字辨識（OCR）/);
  } finally {
    server.close();
  }
});
