import assert from "node:assert/strict";
import { test } from "node:test";
import {
  computeAspectFit,
  screenRectToBitmapRect,
} from "../cropWorkflow";
import type { CropRect } from "../cropGeometry";

test("computeAspectFit centers wider image with letterbox (top/bottom bars)", () => {
  // Container: 1000 x 1000, Image: 1920 x 1080 (16:9)
  const fit = computeAspectFit(1000, 1000, 1920, 1080);
  assert.equal(fit.width, 1000);
  assert.equal(Math.round(fit.height), 563);
  assert.equal(fit.left, 0);
  assert.equal(Math.round(fit.top), 219);
});

test("computeAspectFit centers taller image with pillarbox (left/right bars)", () => {
  // Container: 1000 x 1000, Image: 1080 x 2400 (9:20 portrait)
  const fit = computeAspectFit(1000, 1000, 1080, 2400);
  assert.equal(Math.round(fit.width), 450);
  assert.equal(fit.height, 1000);
  assert.equal(Math.round(fit.left), 275);
  assert.equal(fit.top, 0);
});

test("screenRectToBitmapRect scales screen crop rect to bitmap coordinates and clamps to content bounds", () => {
  const fit = computeAspectFit(1000, 1000, 100, 100); // 1:1, full size 1000x1000
  const screenRect: CropRect = { left: 100, top: 200, right: 300, bottom: 400 };

  const bitmapRect = screenRectToBitmapRect(screenRect, fit, 100, 100);
  assert.deepEqual(bitmapRect, {
    left: 10,
    top: 20,
    right: 30,
    bottom: 40,
  });
});

test("screenRectToBitmapRect clamps outside coordinates to image boundary", () => {
  // Pillarboxed: left=100, width=800, top=0, height=1000. Bitmap: 800 x 1000
  const fit = { left: 100, top: 0, width: 800, height: 1000, scale: 1 };
  const outsideScreenRect: CropRect = { left: 50, top: -10, right: 950, bottom: 1050 };

  const bitmapRect = screenRectToBitmapRect(outsideScreenRect, fit, 800, 1000);
  assert.deepEqual(bitmapRect, {
    left: 0,
    top: 0,
    right: 800,
    bottom: 1000,
  });
});
