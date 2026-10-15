import { authHeaders } from "./authSync";
import { MessageError, msg } from "./message";

export interface DisplaySummary {
  id: number;
  name: string;
  width: number;
  height: number;
  isVirtual: boolean;
  isMirrorActive?: boolean;
}

/**
 * 列出裝置上的顯示器列表。
 */
export async function listDisplays(address: string, token?: string): Promise<DisplaySummary[]> {
  const response = await fetch(`http://${address}/displays`, {
    headers: authHeaders(token),
  });
  if (!response.ok) {
    throw new MessageError(msg("Failed to list displays on the device (HTTP {0})", response.status));
  }
  return (await response.json()) as DisplaySummary[];
}

/**
 * 切換特定顯示器的鏡像管線（主要用於實體螢幕）。
 */
export async function toggleDisplayMirror(address: string, displayId: number, enable: boolean, token?: string): Promise<void> {
  const response = await fetch(`http://${address}/displays/${displayId}/mirror?enable=${enable}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!response.ok) {
    const reason = await response.text();
    throw new MessageError(msg("Failed to toggle mirroring (HTTP {0}): {1}", response.status, reason));
  }
}

/**
 * 在顯示器上注入一次手勢：一個點是 tap（按住 [durationMs]），多個點是沿路徑的 swipe。
 * [points] 是鏡像畫面的比例座標（0..1），App 換算到邏輯空間。
 */
export async function injectInput(
  address: string,
  displayId: number,
  points: [number, number][],
  durationMs: number,
  token?: string,
): Promise<void> {
  const response = await fetch(`http://${address}/displays/${displayId}/input`, {
    method: "POST",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({ points, durationMs }),
  });
  if (!response.ok) {
    throw new MessageError(msg("Failed to send input (HTTP {0}): {1}", response.status, await response.text()));
  }
}
