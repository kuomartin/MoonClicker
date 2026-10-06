/**
 * 本機腳本資料夾與裝置之間的三方比對，不碰檔案系統也不碰網路。key 是 `<腳本 id>/<相對路徑>`，
 * 一律以 `/` 分隔；value 是檔案內容的 sha256，`undefined` 代表該邊沒有這個檔案。base 是上次
 * 兩邊一致時的內容，靠它分辨「哪一邊改過」：沒有 base 的話，兩邊不同時無從判斷該以誰為準。
 */
export type ShaMap = ReadonlyMap<string, string>;

export type SyncAction =
  | { kind: "pull"; key: string }
  | { kind: "push"; key: string }
  | { kind: "deleteLocal"; key: string }
  | { kind: "deleteRemote"; key: string }
  /** 兩邊都改了而且內容不同：本機不動，裝置版本另存一份給使用者手動合併，之後以本機為準。 */
  | { kind: "conflict"; key: string }
  /** 一邊刪除、另一邊修改：保留修改的那一邊，`from` 是修改過的那一邊。 */
  | { kind: "restore"; key: string; from: "local" | "remote" };

export interface SyncPlan {
  actions: SyncAction[];
  /** 兩邊已經一致的 key 與它們的新 base；`undefined` 代表兩邊都沒有了，base 要移除。 */
  inSync: Map<string, string | undefined>;
}

/**
 * [isIgnored] 符合的 key（`.moonclickerignore`）只允許裝置往本機的方向：可以拉下來、可以標成
 * 衝突，但不推送、不刪除裝置上的檔案，也不因為裝置刪了就刪本機的檔案——被排除的檔案在
 * 本機是使用者自己的東西。
 */
export function planSync(base: ShaMap, local: ShaMap, remote: ShaMap, isIgnored: (key: string) => boolean): SyncPlan {
  const actions: SyncAction[] = [];
  const inSync = new Map<string, string | undefined>();
  const keys = new Set([...base.keys(), ...local.keys(), ...remote.keys()]);
  for (const key of [...keys].sort()) {
    const b = base.get(key);
    const l = local.get(key);
    const r = remote.get(key);
    let action: SyncAction | undefined;
    if (l === r) {
      if (l !== b) inSync.set(key, l);
      continue;
    } else if (l === b) {
      action = r === undefined ? { kind: "deleteLocal", key } : { kind: "pull", key };
    } else if (r === b) {
      action = l === undefined ? { kind: "deleteRemote", key } : { kind: "push", key };
    } else if (l === undefined) {
      action = { kind: "restore", key, from: "remote" };
    } else if (r === undefined) {
      action = { kind: "restore", key, from: "local" };
    } else {
      action = { kind: "conflict", key };
    }
    if (isIgnored(key) && !allowedWhenIgnored(action)) continue;
    actions.push(action);
  }
  return { actions, inSync };
}

function allowedWhenIgnored(action: SyncAction): boolean {
  return action.kind === "pull" || action.kind === "conflict" || (action.kind === "restore" && action.from === "remote");
}

export function scriptIdOf(key: string): string {
  return key.slice(0, key.indexOf("/"));
}

export function relPathOf(key: string): string {
  return key.slice(key.indexOf("/") + 1);
}
