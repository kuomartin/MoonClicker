import { authHeaders } from "./authSync";
import type { TemplateRoi } from "./templateSync";

/**
 * 啟動一次「測試模板」的即時比對（見 #？ 測試模板功能）。
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
