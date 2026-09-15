import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CropRect,
  dragResize,
  hitTest,
  normalized,
  pixelRectToLogicalRoi,
  rotateToLogical,
} from "../cropGeometry";

// 比照原生 CropHandlesTest.kt 的測項。
const RADIUS = 24;
const rect: CropRect = { left: 100, top: 100, right: 300, bottom: 200 };

test("no rect always misses", () => {
  assert.equal(hitTest(null, 100, 100, RADIUS), "None");
});

test("each corner is hit within radius", () => {
  assert.equal(hitTest(rect, rect.left, rect.top, RADIUS), "TopLeft");
  assert.equal(hitTest(rect, rect.right, rect.top, RADIUS), "TopRight");
  assert.equal(hitTest(rect, rect.left, rect.bottom, RADIUS), "BottomLeft");
  assert.equal(hitTest(rect, rect.right, rect.bottom, RADIUS), "BottomRight");
});

test("each edge midpoint is hit when clear of the corners", () => {
  const midY = (rect.top + rect.bottom) / 2;
  const midX = (rect.left + rect.right) / 2;

  assert.equal(hitTest(rect, rect.left, midY, RADIUS), "Left");
  assert.equal(hitTest(rect, rect.right, midY, RADIUS), "Right");
  assert.equal(hitTest(rect, midX, rect.top, RADIUS), "Top");
  assert.equal(hitTest(rect, midX, rect.bottom, RADIUS), "Bottom");
});

test("interior away from every edge is the center handle", () => {
  assert.equal(
    hitTest(rect, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2, RADIUS),
    "Center",
  );
});

test("outside the rect and its radius is a miss", () => {
  assert.equal(hitTest(rect, rect.left - RADIUS - 1, rect.top - RADIUS - 1, RADIUS), "None");
  assert.equal(hitTest(rect, rect.right + RADIUS + 5, rect.bottom + RADIUS + 5, RADIUS), "None");
});

test("corners win over edges when both are within radius", () => {
  // 角落判定先於邊——同一個點同時落在「靠近 left」與「靠近 top」的半徑內時，必須回角落而不是任一條邊。
  assert.equal(hitTest(rect, rect.left + 1, rect.top + 1, RADIUS), "TopLeft");
});

test("hit testing works the same on a rect dragged inside-out", () => {
  const inverted: CropRect = { left: rect.right, top: rect.bottom, right: rect.left, bottom: rect.top };
  assert.equal(hitTest(inverted, rect.left, rect.top, RADIUS), "TopLeft");
  assert.equal(
    hitTest(inverted, (rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2, RADIUS),
    "Center",
  );
});

test("dragResize moves only the edges owned by the handle", () => {
  assert.deepEqual(dragResize(rect, "TopLeft", 10, -5), {
    left: 110, top: 95, right: 300, bottom: 200,
  });
  assert.deepEqual(dragResize(rect, "Right", 20, 999), {
    left: 100, top: 100, right: 320, bottom: 200,
  });
  assert.deepEqual(dragResize(rect, "Top", 999, 15), {
    left: 100, top: 115, right: 300, bottom: 200,
  });
});

test("dragResize on the center translates the whole rect", () => {
  assert.deepEqual(dragResize(rect, "Center", 30, -10), {
    left: 130, top: 90, right: 330, bottom: 190,
  });
});

test("dragResize on none is a no-op", () => {
  assert.deepEqual(dragResize(rect, "None", 50, 50), rect);
});

test("normalized swaps crossed edges back into left-le-right, top-le-bottom order", () => {
  const crossed: CropRect = { left: 300, top: 200, right: 100, bottom: 100 };
  assert.deepEqual(normalized(crossed), rect);
  assert.deepEqual(normalized(rect), rect);
});

// 比照原生 ViewportTest.kt 的 rotateQuarterTurn 測項——rotateToLogical 是同一組係數的 TS port。
test("rotateToLogical maps buffer points into VD's logical space at every v", () => {
  const width = 1080;
  const height = 2400;

  assert.deepEqual(rotateToLogical(0, 0, width, height, 0), { x: 0, y: 0 });
  assert.deepEqual(rotateToLogical(width, height, width, height, 0), { x: width, y: height });

  // v=1：邏輯尺寸互換成 2400x1080，buffer 右上角 (width, 0) 轉到邏輯左上角。
  assert.deepEqual(rotateToLogical(width, 0, width, height, 1), { x: 0, y: 0 });
  assert.deepEqual(rotateToLogical(0, height, width, height, 1), { x: height, y: width });

  // v=2：buffer 右下角轉到邏輯左上角。
  assert.deepEqual(rotateToLogical(width, height, width, height, 2), { x: 0, y: 0 });
  assert.deepEqual(rotateToLogical(0, 0, width, height, 2), { x: width, y: height });

  // v=3：buffer 左下角轉到邏輯左上角。
  assert.deepEqual(rotateToLogical(0, height, width, height, 3), { x: 0, y: 0 });
  assert.deepEqual(rotateToLogical(width, 0, width, height, 3), { x: height, y: width });
});

test("rotateToLogical inverts cleanly at every quarter turn", () => {
  const width = 1080;
  const height = 2400;
  const points: Array<[number, number]> = [
    [0, 0], [width, 0], [0, height], [width, height], [300, 777],
  ];

  for (let quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
    const inverseTurns = (4 - quarterTurns) % 4;
    const swapped = quarterTurns === 1 || quarterTurns === 3;
    const [inverseWidth, inverseHeight] = swapped ? [height, width] : [width, height];

    for (const [px, py] of points) {
      const logical = rotateToLogical(px, py, width, height, quarterTurns);
      const back = rotateToLogical(logical.x, logical.y, inverseWidth, inverseHeight, inverseTurns);
      assert.equal(back.x, px, `quarterTurns=${quarterTurns}`);
      assert.equal(back.y, py, `quarterTurns=${quarterTurns}`);
    }
  }
});

// pixelRectToLogicalRoi：#52 驗證過的、把 surface 空間裁切框轉成 templates.json 要存的邏輯空間
// roi。沿用 prototype/issue-52-crop-logical-roi（commit 015ebfc）的案例，四個旋轉各自手算釘住。
test("pixelRectToLogicalRoi is the identity at v=0", () => {
  const cropRect: CropRect = { left: 20, top: 10, right: 80, bottom: 40 };
  assert.deepEqual(pixelRectToLogicalRoi(cropRect, 200, 100, 0), cropRect);
});

test("pixelRectToLogicalRoi at v=90 maps into the swapped logical space", () => {
  const cropRect: CropRect = { left: 20, top: 10, right: 80, bottom: 40 };
  assert.deepEqual(pixelRectToLogicalRoi(cropRect, 200, 100, 1), {
    left: 10, top: 120, right: 40, bottom: 180,
  });
});

test("pixelRectToLogicalRoi at v=180 maps point-symmetrically", () => {
  const cropRect: CropRect = { left: 20, top: 10, right: 80, bottom: 40 };
  assert.deepEqual(pixelRectToLogicalRoi(cropRect, 200, 100, 2), {
    left: 120, top: 60, right: 180, bottom: 90,
  });
});

test("pixelRectToLogicalRoi at v=270 maps into the swapped logical space", () => {
  const cropRect: CropRect = { left: 20, top: 10, right: 80, bottom: 40 };
  assert.deepEqual(pixelRectToLogicalRoi(cropRect, 200, 100, 3), {
    left: 60, top: 20, right: 90, bottom: 80,
  });
});

test("pixelRectToLogicalRoi normalizes a crossed rect before rotating", () => {
  const crossed: CropRect = { left: 80, top: 40, right: 20, bottom: 10 };
  const upright: CropRect = { left: 20, top: 10, right: 80, bottom: 40 };
  for (let quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
    assert.deepEqual(
      pixelRectToLogicalRoi(crossed, 200, 100, quarterTurns),
      pixelRectToLogicalRoi(upright, 200, 100, quarterTurns),
      `quarterTurns=${quarterTurns}`,
    );
  }
});
