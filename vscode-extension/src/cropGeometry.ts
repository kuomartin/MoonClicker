/**
 * 裁切框幾何：純資料與純函式，不 import `vscode`，供 webview 前端直接使用。
 *
 * `hitTest`/`dragResize`/`normalized` port 自原生的 `CropHandles.kt`，`rotateToLogical`/
 * `pixelRectToLogicalRoi` port 自 `Viewport.kt`（`rotateQuarterTurn`/`quarterTurnCoefficients`
 * 的等價實作）。ADR-0013：模板存的是邏輯空間 roi，因此使用者在 surface 空間（VD 自己的
 * rotation v 尚未轉正）畫的裁切框，要先用 `pixelRectToLogicalRoi` 轉成邏輯空間才能存進
 * templates.json；已在 `prototype/issue-52-crop-logical-roi`（commit 015ebfc）驗證過
 * v=0/90/180/270 四個方向都正確，這裡原樣搬過來，不重新推導。
 */

export interface CropRect {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export type DragHandle =
  | "TopLeft"
  | "TopRight"
  | "BottomLeft"
  | "BottomRight"
  | "Top"
  | "Bottom"
  | "Left"
  | "Right"
  | "Center"
  | "None";

export interface LogicalPoint {
  readonly x: number;
  readonly y: number;
}

/** 拖曳中 left/right、top/bottom 可以互相跨過；命中判定與繪製前都要先轉正。 */
export function normalized(rect: CropRect): CropRect {
  return {
    left: Math.min(rect.left, rect.right),
    top: Math.min(rect.top, rect.bottom),
    right: Math.max(rect.left, rect.right),
    bottom: Math.max(rect.top, rect.bottom),
  };
}

/**
 * 判斷觸點落在 `rect` 的哪個 handle 上；`rect` 為 `null`（尚未拉出裁切框）一律回 `"None"`。
 *
 * 角落用圓形距離、邊用單軸距離＋另一軸落在區間內，中心用「落在矩形內但不靠邊」；這個判斷順序
 * 本身就是優先權——角落先於邊，邊先於中心。
 */
export function hitTest(
  rect: CropRect | null,
  x: number,
  y: number,
  handleRadius: number,
): DragHandle {
  if (!rect) return "None";
  const r = normalized(rect);

  const nearPoint = (px: number, py: number) => Math.hypot(x - px, y - py) < handleRadius;
  const nearX = (vx: number) => Math.abs(x - vx) < handleRadius;
  const nearY = (vy: number) => Math.abs(y - vy) < handleRadius;

  if (nearPoint(r.left, r.top)) return "TopLeft";
  if (nearPoint(r.right, r.top)) return "TopRight";
  if (nearPoint(r.left, r.bottom)) return "BottomLeft";
  if (nearPoint(r.right, r.bottom)) return "BottomRight";
  if (nearX(r.left) && y >= r.top && y <= r.bottom) return "Left";
  if (nearX(r.right) && y >= r.top && y <= r.bottom) return "Right";
  if (nearY(r.top) && x >= r.left && x <= r.right) return "Top";
  if (nearY(r.bottom) && x >= r.left && x <= r.right) return "Bottom";
  if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return "Center";
  return "None";
}

/** 依 `handle` 把拖曳量 (dx, dy) 套進對應的邊/角；未夾住的邊維持原值，`"None"` 是 no-op。 */
export function dragResize(rect: CropRect, handle: DragHandle, dx: number, dy: number): CropRect {
  switch (handle) {
    case "TopLeft":
      return { ...rect, left: rect.left + dx, top: rect.top + dy };
    case "TopRight":
      return { ...rect, top: rect.top + dy, right: rect.right + dx };
    case "BottomLeft":
      return { ...rect, left: rect.left + dx, bottom: rect.bottom + dy };
    case "BottomRight":
      return { ...rect, right: rect.right + dx, bottom: rect.bottom + dy };
    case "Top":
      return { ...rect, top: rect.top + dy };
    case "Bottom":
      return { ...rect, bottom: rect.bottom + dy };
    case "Left":
      return { ...rect, left: rect.left + dx };
    case "Right":
      return { ...rect, right: rect.right + dx };
    case "Center":
      return {
        left: rect.left + dx,
        top: rect.top + dy,
        right: rect.right + dx,
        bottom: rect.bottom + dy,
      };
    case "None":
      return rect;
  }
}

/**
 * buffer 空間中的一點旋轉 `quarterTurns` 個直角（VD 自己的 rotation v）到 VD 目前的邏輯空間。
 * port 自 `Viewport.kt` 的 `rotateQuarterTurn`/`quarterTurnCoefficients`：v=1/2/3 分別對應
 * 順時針 90°/180°/270°，v=0 或其他值一律視為不轉。
 */
export function rotateToLogical(
  px: number,
  py: number,
  width: number,
  height: number,
  quarterTurns: number,
): LogicalPoint {
  switch (((quarterTurns % 4) + 4) % 4) {
    case 1:
      return { x: py, y: width - px };
    case 2:
      return { x: width - px, y: height - py };
    case 3:
      return { x: height - py, y: px };
    default:
      return { x: px, y: py };
  }
}

/**
 * 把 surface 空間的 pixel rect 轉成邏輯空間 roi，重用 `rotateToLogical`——不發明新座標系。
 * 旋轉的是矩形的兩個對角點，直角旋轉保證轉完仍是軸對齊矩形，因此轉完再 `normalized` 一次即可。
 */
export function pixelRectToLogicalRoi(
  rect: CropRect,
  bitmapWidth: number,
  bitmapHeight: number,
  quarterTurns: number,
): CropRect {
  const r = normalized(rect);
  const a = rotateToLogical(r.left, r.top, bitmapWidth, bitmapHeight, quarterTurns);
  const b = rotateToLogical(r.right, r.bottom, bitmapWidth, bitmapHeight, quarterTurns);
  return normalized({ left: a.x, top: a.y, right: b.x, bottom: b.y });
}
