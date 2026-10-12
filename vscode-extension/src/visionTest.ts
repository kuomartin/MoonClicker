import { authHeaders } from "./authSync";
import { MessageError, msg } from "./message";
import type { TemplateRoi } from "./templateSync";

/**
 * 啟動一次「測試模板」的即時比對。
 * 呼叫 POST /scripts/{id}/vision-test——裝置端會針對 `displayId` 對應的虛擬顯示器，
 * 產生一支迴圈呼叫 `vision.find` 並透過 `data.set("visionTest", ...)` 回報結果的暫存腳本並啟動。
 * 目標顯示器上已有腳本在跑時裝置回 409。
 *
 * @returns 暫存腳本的 id，停止時交給 [stopRun]。
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
): Promise<string> {
  if (!scriptId || scriptId.includes("/") || scriptId.includes("..")) {
    throw new MessageError(msg("Invalid script ID"));
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
      throw new MessageError(msg("Device is busy (HTTP 409): {0}", reason));
    }
    if (response.status === 404) {
      throw new MessageError(msg("Script not found (HTTP 404): {0}", reason || scriptId));
    }
    throw new MessageError(msg("Failed to start the match test (HTTP {0}): {1}", response.status, reason));
  }
  return response.text();
}

/** `/ocr-test` 的模式，對應 `vision.read`、`vision.read_lines`、`vision.find({ text })`。 */
export type OcrTestMode = "read" | "read_lines" | "find";

export interface OcrTestOptions {
  displayId: number;
  mode: OcrTestMode;
  /** `read` 必填；`read_lines`/`find` 省略時是整張畫面。 */
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
 * `data.set("ocrTest", ...)` 回報結果的暫存腳本。
 *
 * @returns 暫存腳本的 id，停止時交給 [stopRun]。
 */
export async function startOcrTest(address: string, options: OcrTestOptions, token?: string): Promise<string> {
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
      throw new MessageError(msg("Device is busy (HTTP 409): {0}", reason));
    }
    if (response.status === 412) {
      throw new MessageError(
        msg("OCR is not installed on the device. Download it in the app under Settings → Text recognition (OCR) (HTTP 412)"),
      );
    }
    throw new MessageError(msg("Failed to start the OCR test (HTTP {0}): {1}", response.status, reason));
  }
  return response.text();
}

/**
 * 停止 [scriptId]，沒給就停止全部。呼叫 POST /run/stop——沒有東西在跑時也是冪等的（不視為錯誤）。
 */
export async function stopRun(address: string, token?: string, scriptId?: string): Promise<void> {
  const query = scriptId ? `?script=${encodeURIComponent(scriptId)}` : "";
  const url = `http://${address}/run/stop${query}`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      ...authHeaders(token),
    },
  });

  if (!response.ok) {
    const reason = await response.text();
    throw new MessageError(msg("Failed to stop (HTTP {0}): {1}", response.status, reason));
  }
}
