import { authHeaders } from "./authSync";
import type { TemplateRoi } from "./templateSync";

/**
 * 啟動一次「測試模板」的即時比對。
 * 呼叫 POST /scripts/{id}/vision-test——裝置端會針對 `displayId` 對應的虛擬顯示器，
 * 產生一支迴圈呼叫 `vision.find` 並透過 `data.set("visionTest", ...)` 回報結果的暫存腳本並啟動。
 * 與正式腳本共用同一個執行槽，若裝置已在跑東西會回 409。
 */
export async function startVisionTest(
  address: string,
  scriptId: string,
  displayId: number,
  image: string,
  roi: TemplateRoi,
  threshold: number,
  intervalMs: number | undefined,
  token?: string,
): Promise<void> {
  if (!scriptId || scriptId.includes("/") || scriptId.includes("..")) {
    throw new Error("腳本 ID 無效");
  }

  const url = `http://${address}/scripts/${encodeURIComponent(scriptId)}/vision-test`;
  const body: Record<string, unknown> = {
    displayId,
    image,
    roi,
    threshold,
  };
  if (typeof intervalMs === "number") {
    body.intervalMs = intervalMs;
  }

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(token),
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const reason = await response.text();
    if (response.status === 409) {
      throw new Error(`裝置忙碌中，已有腳本在執行（HTTP 409）：${reason}`);
    }
    if (response.status === 404) {
      throw new Error(`找不到腳本（HTTP 404）：${reason || scriptId}`);
    }
    throw new Error(`啟動測試比對失敗（HTTP ${response.status}）：${reason}`);
  }
}

/** `/ocr-test` 的模式，對應 `vision.read`、`vision.read_lines`、`vision.find({ text })`。 */
export type OcrTestMode = "read" | "read_lines" | "find";

export interface OcrTestOptions {
  displayId: number;
  mode: OcrTestMode;
  /** `read` 必填；`read_lines`／`find` 省略時是整張畫面。 */
  roi?: TemplateRoi | null;
  /** `find` 必填。 */
  text?: string;
  exact?: boolean;
  threshold?: number;
  intervalMs?: number;
}

/** `/ocr-test` 的 request body；只放該模式用得到的欄位。 */
export function ocrTestBody(options: OcrTestOptions): Record<string, unknown> {
  const body: Record<string, unknown> = { displayId: options.displayId, mode: options.mode };
  if (options.roi) body.roi = options.roi;
  if (options.mode === "find") {
    body.text = options.text ?? "";
    body.exact = options.exact ?? false;
    if (typeof options.threshold === "number") body.threshold = options.threshold;
  }
  if (typeof options.intervalMs === "number") body.intervalMs = options.intervalMs;
  return body;
}

/**
 * 啟動一次 OCR 測試：裝置端在 `displayId` 上跑一支迴圈呼叫 OCR API、透過
 * `data.set("ocrTest", ...)` 回報結果的暫存腳本。與正式腳本共用同一個執行槽。
 */
export async function startOcrTest(address: string, options: OcrTestOptions, token?: string): Promise<void> {
  const response = await fetch(`http://${address}/ocr-test`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(token),
    },
    body: JSON.stringify(ocrTestBody(options)),
  });

  if (!response.ok) {
    const reason = await response.text();
    if (response.status === 409) {
      throw new Error(`裝置忙碌中，已有腳本在執行（HTTP 409）：${reason}`);
    }
    if (response.status === 412) {
      throw new Error("裝置尚未安裝 OCR 元件：請到裝置的「設定 → 文字辨識（OCR）」下載（HTTP 412）");
    }
    throw new Error(`啟動 OCR 測試失敗（HTTP ${response.status}）：${reason}`);
  }
}

/**
 * 停止目前正在執行的項目（無論是正式腳本還是測試比對），兩者共用同一個執行槽。
 * 呼叫 POST /run/stop——即使裝置端目前沒有任何東西在跑，這支呼叫也必須是冪等的（不視為錯誤）。
 */
export async function stopRun(address: string, token?: string): Promise<void> {
  const url = `http://${address}/run/stop`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      ...authHeaders(token),
    },
  });

  if (!response.ok) {
    const reason = await response.text();
    throw new Error(`停止執行失敗（HTTP ${response.status}）：${reason}`);
  }
}
