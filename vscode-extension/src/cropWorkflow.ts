import { type CropRect, normalized } from "./cropGeometry";

export interface AspectFitResult {
  readonly left: number;
  readonly top: number;
  readonly width: number;
  readonly height: number;
  readonly scale: number;
}

/**
 * 計算影像在容器內的 aspect-fit 渲染位置與縮放比例（重現 letterbox / pillarbox）。
 */
export function computeAspectFit(
  containerWidth: number,
  containerHeight: number,
  imageWidth: number,
  imageHeight: number,
): AspectFitResult {
  if (containerWidth <= 0 || containerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
    return { left: 0, top: 0, width: 0, height: 0, scale: 1 };
  }

  const containerAspect = containerWidth / containerHeight;
  const imageAspect = imageWidth / imageHeight;

  let width: number;
  let height: number;
  let scale: number;

  if (containerAspect > imageAspect) {
    // 容器較寬：左右留黑邊（pillarbox），高度撐滿
    scale = containerHeight / imageHeight;
    width = imageWidth * scale;
    height = containerHeight;
  } else {
    // 容器較高或相等：上下留黑邊（letterbox），寬度撐滿
    scale = containerWidth / imageWidth;
    width = containerWidth;
    height = imageHeight * scale;
  }

  const left = (containerWidth - width) / 2;
  const top = (containerHeight - height) / 2;

  return { left, top, width, height, scale };
}

/**
 * 將螢幕/容器座標系下的裁切框轉成 bitmap 空間的像素座標，並 clamp 在圖片實際內容範圍內。
 */
export function screenRectToBitmapRect(
  screenRect: CropRect,
  fit: AspectFitResult,
  bitmapWidth: number,
  bitmapHeight: number,
): CropRect {
  const norm = normalized(screenRect);
  if (fit.scale <= 0) {
    return { left: 0, top: 0, right: 0, bottom: 0 };
  }

  // 限制在 fit.left .. fit.left + fit.width 與 fit.top .. fit.top + fit.height
  const clampedLeft = Math.max(fit.left, Math.min(fit.left + fit.width, norm.left));
  const clampedRight = Math.max(fit.left, Math.min(fit.left + fit.width, norm.right));
  const clampedTop = Math.max(fit.top, Math.min(fit.top + fit.height, norm.top));
  const clampedBottom = Math.max(fit.top, Math.min(fit.top + fit.height, norm.bottom));

  // 轉換至 bitmap 像素座標
  const bmLeft = Math.max(0, Math.min(bitmapWidth, (clampedLeft - fit.left) / fit.scale));
  const bmRight = Math.max(0, Math.min(bitmapWidth, (clampedRight - fit.left) / fit.scale));
  const bmTop = Math.max(0, Math.min(bitmapHeight, (clampedTop - fit.top) / fit.scale));
  const bmBottom = Math.max(0, Math.min(bitmapHeight, (clampedBottom - fit.top) / fit.scale));

  return normalized({
    left: Math.round(bmLeft),
    top: Math.round(bmTop),
    right: Math.round(bmRight),
    bottom: Math.round(bmBottom),
  });
}
