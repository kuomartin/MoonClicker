import AdmZip from "adm-zip";

export interface ScriptSummary {
  id: string;
  name: string;
}

/**
 * 裝置端 Script Folder 同步的純邏輯（見 #58），不依賴 vscode API——單元測試對著一個
 * 本機起的假 HTTP server 跑，`extension.ts` 只負責接 UI（選腳本、選資料夾）。
 */
export async function listScripts(address: string): Promise<ScriptSummary[]> {
  const response = await fetch(`http://${address}/scripts`);
  if (!response.ok) {
    throw new Error(`列出裝置上的腳本失敗（HTTP ${response.status}）`);
  }
  return (await response.json()) as ScriptSummary[];
}

/** Pull：把裝置上 id 對應的 Script Folder 展開到本機的 [destDir]，整份覆蓋掉既有內容。 */
export async function pullScript(address: string, id: string, destDir: string): Promise<void> {
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/export`);
  if (!response.ok) {
    throw new Error(`Pull 失敗（HTTP ${response.status}）`);
  }
  const buffer = Buffer.from(await response.arrayBuffer());
  new AdmZip(buffer).extractAllTo(destDir, true);
}

/** Push：把本機 [sourceDir] 的內容整份推回裝置上 id 對應的 Script Folder，整份覆蓋掉。 */
export async function pushScript(address: string, id: string, sourceDir: string): Promise<void> {
  const zip = new AdmZip();
  zip.addLocalFolder(sourceDir);
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/import`, {
    method: "PUT",
    body: new Uint8Array(zip.toBuffer()),
  });
  if (!response.ok) {
    const reason = await response.text();
    throw new Error(`Push 失敗（HTTP ${response.status}）：${reason}`);
  }
}
