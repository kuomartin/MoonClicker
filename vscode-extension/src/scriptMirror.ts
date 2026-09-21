import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import { deleteEntry, getTree, mkdir as mkdirRemote, readFile, renameEntry, writeFile } from "./scriptSync";

export function sha256Hex(content: Uint8Array | Buffer): string {
  return createHash("sha256").update(content).digest("hex");
}

/** `context.globalStorageUri.fsPath` 底下這顆腳本的隱藏鏡像資料夾路徑（見 vscode-local-mirror-plan.md）。 */
export function mirrorDir(globalStorageDir: string, address: string, scriptId: string): string {
  const safeAddress = address.replace(/[^a-zA-Z0-9.-]/g, "_");
  return path.join(globalStorageDir, "mirrors", safeAddress, scriptId);
}

function toLocalPath(destDir: string, relativePath: string): string {
  return path.join(destDir, ...relativePath.split("/"));
}

function toRelativePath(destDir: string, localPath: string): string {
  return path.relative(destDir, localPath).split(path.sep).join("/");
}

/**
 * 整份下載腳本到 [destDir]：跟本機已有的內容雜湊相同的檔案跳過下載。只新增/覆蓋，不刪除
 * 裝置上已經不存在、但本機還留著的檔案——跟舊版 pull（zip 展開）的行為一致，不是這次要
 * 補的功能。
 */
export async function pullScriptToMirror(
  address: string,
  scriptId: string,
  destDir: string,
  token?: string,
): Promise<void> {
  const tree = await getTree(address, scriptId, token);
  fs.mkdirSync(destDir, { recursive: true });

  const dirs = tree
    .filter((e) => e.isDirectory)
    .sort((a, b) => a.path.split("/").length - b.path.split("/").length);
  for (const dir of dirs) {
    fs.mkdirSync(toLocalPath(destDir, dir.path), { recursive: true });
  }

  for (const file of tree.filter((e) => !e.isDirectory)) {
    const localPath = toLocalPath(destDir, file.path);
    if (fs.existsSync(localPath) && sha256Hex(fs.readFileSync(localPath)) === file.sha256) {
      continue;
    }
    const content = await readFile(address, scriptId, file.path, token);
    fs.mkdirSync(path.dirname(localPath), { recursive: true });
    fs.writeFileSync(localPath, content);
  }
}

/** 存檔／新增檔案時呼叫：把本機這個檔案的目前內容推上裝置。 */
export async function pushFileToDevice(
  address: string,
  scriptId: string,
  destDir: string,
  localPath: string,
  token?: string,
): Promise<void> {
  const content = fs.readFileSync(localPath);
  await writeFile(address, scriptId, toRelativePath(destDir, localPath), content, token);
}

export async function createDirectoryOnDevice(
  address: string,
  scriptId: string,
  destDir: string,
  localPath: string,
  token?: string,
): Promise<void> {
  await mkdirRemote(address, scriptId, toRelativePath(destDir, localPath), token);
}

export async function deleteFileOnDevice(
  address: string,
  scriptId: string,
  destDir: string,
  localPath: string,
  token?: string,
): Promise<void> {
  await deleteEntry(address, scriptId, toRelativePath(destDir, localPath), token);
}

export async function renameFileOnDevice(
  address: string,
  scriptId: string,
  destDir: string,
  oldLocalPath: string,
  newLocalPath: string,
  token?: string,
): Promise<void> {
  await renameEntry(
    address,
    scriptId,
    toRelativePath(destDir, oldLocalPath),
    toRelativePath(destDir, newLocalPath),
    true,
    token,
  );
}

/**
 * 收到裝置推來的 `file_change`（CREATED/CHANGED）時呼叫：重新抓那個檔案的內容，跟本機
 * 現有內容雜湊一致就不寫入——這就是 self-echo 的判斷本身：如果這是自己剛推上去、裝置又
 * 廣播回來的回音，抓回來的內容必然跟本機現在這份一模一樣。不需要另外猜時間窗。
 *
 * @returns 是否真的寫入了本機檔案（呼叫端可能要 reload 開著的 buffer）。
 */
export async function syncChangedFileToMirror(
  address: string,
  scriptId: string,
  destDir: string,
  relativePath: string,
  token?: string,
): Promise<boolean> {
  const content = await readFile(address, scriptId, relativePath, token);
  const localPath = toLocalPath(destDir, relativePath);
  if (fs.existsSync(localPath) && sha256Hex(fs.readFileSync(localPath)) === sha256Hex(content)) {
    return false;
  }
  fs.mkdirSync(path.dirname(localPath), { recursive: true });
  fs.writeFileSync(localPath, content);
  return true;
}

/** 收到裝置推來的 `file_change`（DELETED）時呼叫。@returns 本機是否真的有東西被刪掉。 */
export function removeMirroredFile(destDir: string, relativePath: string): boolean {
  const localPath = toLocalPath(destDir, relativePath);
  if (!fs.existsSync(localPath)) return false;
  fs.rmSync(localPath, { recursive: true, force: true });
  return true;
}
