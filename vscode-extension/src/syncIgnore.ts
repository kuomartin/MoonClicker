import * as fs from "node:fs";
import * as path from "node:path";
import ignore from "ignore";

export const IGNORE_FILE_NAME = ".moonclickerignore";

/** 同步衝突時，裝置版本另存成 `<檔名>.device` 放在本機檔案旁邊；它只給使用者合併用，不推回裝置。 */
export const CONFLICT_SUFFIX = ".device";

/**
 * 永遠先套用的規則，使用者可以在 ignorefile 裡用 `!` 否定。`.luarc.json` 的
 * `workspace.library` 指向本機 extension 目錄，推上裝置沒有意義；ignorefile 本身與衝突時
 * 另存的裝置版本也只對本機有意義。
 */
export const DEFAULT_IGNORE_RULES = [".git/", ".vscode/", ".luarc.json", IGNORE_FILE_NAME, `*${CONFLICT_SUFFIX}`];

/** `relPath` 是相對於 Script Folder、以 `/` 分隔的路徑；回傳 `true` 代表不要推上裝置。 */
export type SyncFilter = (relPath: string, isDirectory: boolean) => boolean;

export function createSyncFilter(ignoreFileContent: string | undefined): SyncFilter {
  const matcher = ignore().add(DEFAULT_IGNORE_RULES);
  if (ignoreFileContent !== undefined) matcher.add(ignoreFileContent);
  // `build/` 這類結尾斜線的規則只擋目錄，`ignore` 靠路徑結尾的 `/` 分辨。
  return (relPath, isDirectory) => matcher.ignores(isDirectory ? `${relPath}/` : relPath);
}

/**
 * 每次同步事件都重新讀 ignorefile：檔案很小、事件是人手存檔的頻率，不快取就不用處理
 * 改了 ignorefile 之後的失效。
 */
export function loadSyncFilter(destDir: string): SyncFilter {
  let content: string | undefined;
  try {
    content = fs.readFileSync(path.join(destDir, IGNORE_FILE_NAME), "utf8");
  } catch {
    content = undefined;
  }
  return createSyncFilter(content);
}
