import { authHeaders } from "./authSync";

export interface TemplateRoi {
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
}

/**
 * 去除前後空白，並移除尾隨的 `.png` 副檔名（不分大小寫），取得規範化的模板名稱。
 */
export function normalizeTemplateName(name: string): string {
  const trimmed = name.trim();
  return trimmed.replace(/\.png$/i, "");
}

/**
 * 檢查 candidateName 是否與已存在的模板名稱列表衝突（不分大小寫、且不分是否帶有 .png）。
 */
export function checkTemplateNameConflict(existingNames: string[], candidateName: string): boolean {
  const normTarget = normalizeTemplateName(candidateName).toLowerCase();
  if (!normTarget) return false;
  return existingNames.some((existing) => normalizeTemplateName(existing).toLowerCase() === normTarget);
}

/**
 * 裁切模板寫入裝置端 Script Folder（見 #75、#78）。
 * 呼叫 PUT /scripts/{id}/templates/{name}?x=...&y=...&w=...&h=...
 */
export async function saveTemplate(
  address: string,
  scriptId: string,
  templateName: string,
  roi: TemplateRoi,
  pngBytes: Uint8Array,
  token?: string,
): Promise<void> {
  const normName = normalizeTemplateName(templateName);
  if (!normName) {
    throw new Error("模板名稱不可為空");
  }
  if (normName.includes("/") || normName.includes("..")) {
    throw new Error("模板名稱不可包含「/」或「..」");
  }
  if (!scriptId || scriptId.includes("/") || scriptId.includes("..")) {
    throw new Error("腳本 ID 無效");
  }

  const query = new URLSearchParams({
    x: Math.round(roi.x).toString(),
    y: Math.round(roi.y).toString(),
    w: Math.round(roi.w).toString(),
    h: Math.round(roi.h).toString(),
  });

  const url = `http://${address}/scripts/${encodeURIComponent(scriptId)}/templates/${encodeURIComponent(normName)}?${query.toString()}`;

  const response = await fetch(url, {
    method: "PUT",
    headers: {
      "Content-Type": "image/png",
      ...authHeaders(token),
    },
    body: pngBytes,
  });

  if (!response.ok) {
    const reason = await response.text();
    if (response.status === 409) {
      throw new Error(`模板已存在（HTTP 409）：${reason || normName}`);
    }
    throw new Error(`儲存模板失敗（HTTP ${response.status}）：${reason}`);
  }
}

export interface TemplateSummary {
  readonly name: string;
  readonly roi: TemplateRoi;
}

/**
 * 列出某腳本目前存的所有模板（見 vision-test 計畫第 2 階段）。
 * 呼叫 GET /scripts/{id}/templates，回傳跟 templates.json 一樣的 `{ 檔名: { roi } }` map。
 */
export async function listTemplates(address: string, scriptId: string, token?: string): Promise<TemplateSummary[]> {
  const url = `http://${address}/scripts/${encodeURIComponent(scriptId)}/templates`;
  const response = await fetch(url, { headers: authHeaders(token) });
  if (!response.ok) {
    throw new Error(`列出模板失敗（HTTP ${response.status}）：${await response.text()}`);
  }
  const raw = (await response.json()) as Record<string, { roi: TemplateRoi }>;
  return Object.entries(raw).map(([name, entry]) => ({ name, roi: entry.roi }));
}

/**
 * 刪除某腳本的一個模板（見 vision-test 計畫第 2 階段）。
 * 呼叫 DELETE /scripts/{id}/templates/{name}——裝置端會把圖片檔跟 templates.json 裡的
 * 那一筆一起刪掉。
 */
export async function deleteTemplate(
  address: string,
  scriptId: string,
  templateName: string,
  token?: string,
): Promise<void> {
  const normName = normalizeTemplateName(templateName);
  if (!normName) {
    throw new Error("模板名稱不可為空");
  }
  if (normName.includes("/") || normName.includes("..")) {
    throw new Error("模板名稱不可包含「/」或「..」");
  }
  if (!scriptId || scriptId.includes("/") || scriptId.includes("..")) {
    throw new Error("腳本 ID 無效");
  }

  const url = `http://${address}/scripts/${encodeURIComponent(scriptId)}/templates/${encodeURIComponent(normName)}`;
  const response = await fetch(url, {
    method: "DELETE",
    headers: authHeaders(token),
  });

  if (!response.ok) {
    const reason = await response.text();
    if (response.status === 404) {
      throw new Error(`找不到模板（HTTP 404）：${reason || normName}`);
    }
    throw new Error(`刪除模板失敗（HTTP ${response.status}）：${reason}`);
  }
}
