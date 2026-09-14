/**
 * `.luarc.json` 的 `workspace.library` 掛載邏輯（見 #59）。只補缺的欄位，不覆蓋使用者
 * 既有設定的其餘部分——stub 隨 extension 打包，不寫進裝置同步的 Script Folder，避免污染
 * 使用者 git 追蹤的腳本資料夾、避免 multi-root workspace 撞出不同版本。
 */
export type Luarc = Record<string, unknown>;

export function mergeLuarc(existing: Luarc, stubPath: string): Luarc {
  const workspace = { ...((existing.workspace as Luarc | undefined) ?? {}) };
  const library = Array.isArray(workspace.library) ? [...(workspace.library as string[])] : [];
  if (!library.includes(stubPath)) {
    library.push(stubPath);
  }
  return {
    ...existing,
    workspace: { ...workspace, library },
  };
}
