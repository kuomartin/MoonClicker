import { authHeaders } from "./authSync";

export interface ScriptSummary {
  id: string;
  name: string;
  /** 裝置端 templates.json 的模板數。舊版裝置韌體不帶這個欄位時是 undefined，不是 0。 */
  templateCount?: number;
  /** `main.lua`／`templates.json` 兩者 mtime 取大的 epoch ms。理由同上，可能是 undefined。 */
  modifiedMs?: number;
}

export interface ScriptTreeEntry {
  path: string;
  size: number;
  mtimeMs: number;
  isDirectory: boolean;
  /** 目錄是空字串；`scriptMirror.ts` 拿這個判斷 self-echo、跳過內容沒變的檔案。 */
  sha256: string;
}

/** 裝置端 HTTP 狀態碼原樣帶出來，呼叫端不用回頭解析錯誤訊息字串就能判斷要怎麼處理。 */
export class ScriptHttpError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
  }
}

/**
 * 裝置端 Script Folder 同步的純邏輯（見 #58、vscode-local-mirror-plan.md），不依賴 vscode
 * API——單元測試對著一個本機起的假 HTTP server 跑，`scriptMirror.ts` 拿這層去驅動本機
 * 鏡像資料夾的背景同步。
 */
export async function listScripts(address: string, token?: string): Promise<ScriptSummary[]> {
  const response = await fetch(`http://${address}/scripts`, {
    headers: authHeaders(token),
  });
  if (!response.ok) {
    throw new ScriptHttpError(`列出裝置上的腳本失敗（HTTP ${response.status}）`, response.status);
  }
  return (await response.json()) as ScriptSummary[];
}

/** 整個 Script Folder 的遞迴檔案清單，`FileSystemProvider` 的 stat/readDirectory 靠這個做 cache。 */
export async function getTree(address: string, id: string, token?: string): Promise<ScriptTreeEntry[]> {
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/tree`, {
    headers: authHeaders(token),
  });
  if (!response.ok) {
    throw new ScriptHttpError(`讀取腳本檔案樹失敗（HTTP ${response.status}）`, response.status);
  }
  return (await response.json()) as ScriptTreeEntry[];
}

function filesUrl(address: string, id: string, path: string): string {
  const segments = path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  return `http://${address}/scripts/${encodeURIComponent(id)}/files/${segments}`;
}

export async function readFile(address: string, id: string, path: string, token?: string): Promise<Uint8Array> {
  const response = await fetch(filesUrl(address, id, path), { headers: authHeaders(token) });
  if (!response.ok) {
    throw new ScriptHttpError(`讀取檔案失敗（HTTP ${response.status}）：${path}`, response.status);
  }
  return new Uint8Array(await response.arrayBuffer());
}

export async function writeFile(
  address: string,
  id: string,
  path: string,
  content: Uint8Array,
  token?: string,
): Promise<void> {
  const response = await fetch(filesUrl(address, id, path), {
    method: "PUT",
    headers: authHeaders(token),
    body: content,
  });
  if (!response.ok) {
    throw new ScriptHttpError(`寫入檔案失敗（HTTP ${response.status}）：${path}`, response.status);
  }
}

export async function deleteEntry(address: string, id: string, path: string, token?: string): Promise<void> {
  const response = await fetch(filesUrl(address, id, path), {
    method: "DELETE",
    headers: authHeaders(token),
  });
  if (!response.ok) {
    throw new ScriptHttpError(`刪除失敗（HTTP ${response.status}）：${path}`, response.status);
  }
}

export async function mkdir(address: string, id: string, path: string, token?: string): Promise<void> {
  const segments = path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/mkdir/${segments}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!response.ok) {
    throw new ScriptHttpError(`建立資料夾失敗（HTTP ${response.status}）：${path}`, response.status);
  }
}

export async function renameEntry(
  address: string,
  id: string,
  from: string,
  to: string,
  overwrite: boolean,
  token?: string,
): Promise<void> {
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/rename`, {
    method: "POST",
    headers: { ...authHeaders(token), "content-type": "application/json" },
    body: JSON.stringify({ from, to, overwrite }),
  });
  if (!response.ok) {
    const reason = await response.text();
    throw new ScriptHttpError(reason || `改名失敗（HTTP ${response.status}）`, response.status);
  }
}

/**
 * `id` 同時是資料夾名跟 `script.json` 的 `uniqueId`（見裝置端 `Script.UNIQUE_ID_PATTERN`），
 * 兩邊共用同一條規則，不在這裡另外維護一份。
 */
const SCRIPT_ID_PATTERN = /^[a-z0-9._-]+$/;

/**
 * 新增一個空的、可執行的腳本資料夾（見 vision-test 計畫第 4 階段：「編寫」的新增腳本）。
 * 呼叫 POST /scripts/{id}，裝置端會給一份最小的 main.lua 骨架跟帶 uniqueId 的 script.json。
 */
export async function createScript(address: string, id: string, token?: string): Promise<void> {
  if (!id || id.startsWith(".") || !SCRIPT_ID_PATTERN.test(id)) {
    throw new Error("腳本名稱只能是小寫英數字、「.」「_」「-」，且不能以「.」開頭");
  }
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!response.ok) {
    const reason = await response.text();
    if (response.status === 409) {
      throw new Error(`腳本已存在（HTTP 409）：${reason || id}`);
    }
    throw new ScriptHttpError(reason || `新增腳本失敗（HTTP ${response.status}）`, response.status);
  }
}

/**
 * 觸發裝置上已同步的腳本執行（見 #61）——裝置端統一經過既有 ScriptSession，這裡只是
 * 多一個外部呼叫入口，不建立第二條執行路徑。
 */
export async function runScript(address: string, id: string, token?: string): Promise<void> {
  const response = await fetch(`http://${address}/scripts/${encodeURIComponent(id)}/run`, {
    method: "POST",
    headers: authHeaders(token),
  });
  if (!response.ok) {
    const reason = await response.text();
    throw new Error(`執行失敗（HTTP ${response.status}）：${reason}`);
  }
}
