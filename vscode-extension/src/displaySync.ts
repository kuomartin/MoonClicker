import { authHeaders } from "./authSync";

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
    throw new Error(`列出裝置上的顯示器失敗（HTTP ${response.status}）`);
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
    throw new Error(`切換鏡像失敗（HTTP ${response.status}）：${reason}`);
  }
}
