import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import {
  createScript,
  deleteEntry,
  deleteScript,
  getTree,
  isValidScriptId,
  listScripts,
  readFile,
  ScriptHttpError,
  writeFile,
} from "./scriptSync";
import { CONFLICT_SUFFIX, loadSyncFilter, type SyncFilter } from "./syncIgnore";
import { planSync, relPathOf, scriptIdOf, type SyncAction } from "./syncPlan";

/**
 * 本機的腳本資料夾（使用者選的一個資料夾，底下每個子資料夾是一支腳本）與裝置 `scripts/`
 * 之間的同步。連線時做一次完整的三方比對（[fullSync]），連線期間再逐一處理本機與裝置推來的
 * 變動。所有操作排進同一條佇列依序執行：連線時的完整同步寫入本機檔案會觸發 watcher，
 * 排在後面處理時 base 已經更新，比對結果自然是「沒有變」，不需要另外過濾回音。
 *
 * 不依賴 vscode API，單元測試對著假的裝置 HTTP server 跑。
 */

export const STATE_DIR = ".moonclicker";
const STATE_FILE = "state.json";

interface SyncState {
  deviceId: string;
  /** key（`<腳本 id>/<相對路徑>`）→ 上次兩邊一致時的 sha256。 */
  files: Record<string, string>;
}

export interface SyncReport {
  pulled: string[];
  pushed: string[];
  deletedLocal: string[];
  deletedRemote: string[];
  /** 兩邊都改了：本機不動，裝置版本存成旁邊的 `.device` 檔；之後以本機版本為準。 */
  conflicts: string[];
  /** 一邊刪除、另一邊修改：保留了修改的版本。 */
  restored: string[];
  /** 沒有同步的腳本與原因，例如名稱不合規則、使用者拒絕刪除。 */
  skipped: { scriptId: string; reason: string }[];
  errors: { key: string; message: string }[];
}

export function emptyReport(): SyncReport {
  return { pulled: [], pushed: [], deletedLocal: [], deletedRemote: [], conflicts: [], restored: [], skipped: [], errors: [] };
}

export interface FolderSyncOptions {
  root: string;
  deviceId: string;
  address: string;
  token?: string;
  /**
   * 要刪除裝置上整支腳本前詢問。本機的腳本資料夾整個不見，可能只是使用者搬走了根目錄或
   * 誤刪，不能直接把裝置上的腳本一起刪掉。回傳 false 時保留裝置上的腳本，下次同步會重新拉回本機。
   */
  confirmDeleteScripts: (scriptIds: string[]) => Promise<boolean>;
}

export function readSyncState(root: string): SyncState | undefined {
  try {
    const state = JSON.parse(fs.readFileSync(path.join(root, STATE_DIR, STATE_FILE), "utf8")) as SyncState;
    return typeof state.deviceId === "string" && state.files && typeof state.files === "object" ? state : undefined;
  } catch {
    return undefined;
  }
}

function sha256(content: Uint8Array): string {
  return createHash("sha256").update(content).digest("hex");
}

export class FolderSync {
  readonly root: string;
  private readonly options: FolderSyncOptions;
  private readonly base: Map<string, string>;
  private remoteScripts = new Set<string>();
  private queue: Promise<unknown> = Promise.resolve();

  constructor(options: FolderSyncOptions) {
    this.options = options;
    this.root = path.resolve(options.root);
    const state = readSyncState(this.root);
    if (state && state.deviceId !== options.deviceId) {
      throw new Error("這個資料夾已經和另一台裝置同步，請選擇其他資料夾");
    }
    this.base = new Map(Object.entries(state?.files ?? {}));
  }

  /** 本機路徑對應的 key；不在任何腳本資料夾裡（根目錄的檔案、`.` 開頭的資料夾）回傳 undefined。 */
  keyFor(fsPath: string): string | undefined {
    const rel = path.relative(this.root, path.resolve(fsPath));
    if (!rel || rel.startsWith("..") || path.isAbsolute(rel)) return undefined;
    const segments = rel.split(path.sep);
    if (segments[0].startsWith(".")) return undefined;
    return segments.join("/");
  }

  scriptIdFor(fsPath: string): string | undefined {
    const key = this.keyFor(fsPath);
    return key === undefined ? undefined : key.split("/")[0];
  }

  localPath(key: string): string {
    return path.join(this.root, ...key.split("/"));
  }

  fullSync(): Promise<SyncReport> {
    return this.enqueue(() => this.runFullSync());
  }

  /** watcher 回報本機檔案或資料夾新增、修改。資料夾會逐一處理底下的檔案。 */
  localChanged(fsPath: string): Promise<SyncReport> {
    return this.enqueue(async () => {
      const report = emptyReport();
      const key = this.keyFor(fsPath);
      if (key === undefined) return report;
      const scriptId = firstSegment(key);
      if (!this.remoteScripts.has(scriptId)) {
        // 新的腳本要等 main.lua 出現才建立；建立後整個資料夾一起推上去。
        if (fs.existsSync(path.join(this.root, scriptId, "main.lua"))) await this.createRemoteScript(scriptId, report);
        return report;
      }
      for (const file of this.walkFiles(this.localPath(key))) {
        await this.pushIfChanged(this.keyFor(file)!, report);
      }
      this.saveState();
      return report;
    });
  }

  /** watcher 回報本機檔案或資料夾被刪除。 */
  localDeleted(fsPath: string): Promise<SyncReport> {
    return this.enqueue(async () => {
      const report = emptyReport();
      const key = this.keyFor(fsPath);
      if (key === undefined) return report;
      const keys = this.baseKeysUnder(key);
      if (!key.includes("/") && this.remoteScripts.has(key)) {
        await this.deleteRemoteScripts([key], report);
      } else {
        const ignored = this.filterFor(firstSegment(key));
        for (const k of keys) {
          if (ignored(relPathOf(k), false)) {
            this.base.delete(k);
            continue;
          }
          await this.attempt(k, report, async () => {
            await this.deleteRemoteFile(k);
            this.base.delete(k);
            report.deletedRemote.push(k);
          });
        }
      }
      this.saveState();
      return report;
    });
  }

  /** 裝置推來的 file_change，或新增腳本後要拉下整支腳本。`relPath` 是空字串代表整支腳本。 */
  remoteChanged(scriptId: string, relPath: string, kind: "changed" | "deleted"): Promise<SyncReport> {
    return this.enqueue(async () => {
      const report = emptyReport();
      if (kind === "deleted") {
        const prefix = relPath ? `${scriptId}/${relPath}` : scriptId;
        for (const key of this.baseKeysUnder(prefix)) this.applyRemoteDeletion(key, report);
        if (!relPath) {
          this.remoteScripts.delete(scriptId);
          this.pruneEmptyDirs(path.join(this.root, scriptId));
        }
      } else {
        this.remoteScripts.add(scriptId);
        if (!relPath) {
          await this.pullTree(scriptId, "", report);
        } else {
          try {
            const content = await readFile(this.options.address, scriptId, relPath, this.options.token);
            await this.applyRemoteContent(`${scriptId}/${relPath}`, content, report);
          } catch (err) {
            // 資料夾（mkdir、整個資料夾改名）讀不到內容：改抓檔案樹，逐一處理底下的檔案。
            if (!(err instanceof ScriptHttpError && err.status === 404)) throw err;
            await this.pullTree(scriptId, relPath, report);
          }
        }
      }
      this.saveState();
      return report;
    });
  }

  /** 拉下裝置上 [scriptId] 的 [relDir] 底下所有檔案；[relDir] 是空字串代表整支腳本。 */
  private async pullTree(scriptId: string, relDir: string, report: SyncReport): Promise<void> {
    const tree = await getTree(this.options.address, scriptId, this.options.token);
    for (const entry of tree) {
      if (entry.isDirectory) continue;
      if (relDir && entry.path !== relDir && !entry.path.startsWith(`${relDir}/`)) continue;
      const key = `${scriptId}/${entry.path}`;
      if (this.localSha(key) === entry.sha256) {
        this.base.set(key, entry.sha256);
        continue;
      }
      await this.attempt(key, report, async () =>
        this.applyRemoteContent(key, await readFile(this.options.address, scriptId, entry.path, this.options.token), report),
      );
    }
  }

  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const run = this.queue.then(task, task);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private async runFullSync(): Promise<SyncReport> {
    const report = emptyReport();
    const { address, token } = this.options;
    const scripts = await listScripts(address, token);
    this.remoteScripts = new Set(scripts.map((s) => s.id));
    const remote = new Map<string, string>();
    for (const script of scripts) {
      for (const entry of await getTree(address, script.id, token)) {
        if (!entry.isDirectory) remote.set(`${script.id}/${entry.path}`, entry.sha256);
      }
    }
    const local = this.listLocal();
    const localScripts = new Set([...local.keys()].map((k) => scriptIdOf(k)));

    // 本機新增、裝置還沒有的腳本：名稱合法且有 main.lua 才建立，否則整支略過。
    const skippedScripts = new Set<string>();
    for (const scriptId of localScripts) {
      if (this.remoteScripts.has(scriptId) || this.hasBaseUnder(scriptId)) continue;
      const reason = this.newScriptProblem(scriptId);
      if (reason) {
        skippedScripts.add(scriptId);
        report.skipped.push({ scriptId, reason });
      }
    }

    const filters = new Map<string, SyncFilter>();
    const isIgnored = (key: string) => {
      const scriptId = scriptIdOf(key);
      if (skippedScripts.has(scriptId)) return true;
      let filter = filters.get(scriptId);
      if (!filter) filters.set(scriptId, (filter = this.filterFor(scriptId)));
      return filter(relPathOf(key), false);
    };
    const plan = planSync(this.base, local, remote, isIgnored);
    for (const [key, sha] of plan.inSync) {
      if (sha === undefined) this.base.delete(key);
      else this.base.set(key, sha);
    }

    // 本機整支腳本資料夾不見了、裝置上的那支也沒改過：整支刪除，而且要先問過。
    const remoteKeysByScript = groupByScript(remote.keys());
    const deleteRemoteKeys = new Set(plan.actions.filter((a) => a.kind === "deleteRemote").map((a) => a.key));
    const wholeScriptDeletes = [...remoteKeysByScript.entries()]
      .filter(([scriptId, keys]) => !localScripts.has(scriptId) && keys.every((k) => deleteRemoteKeys.has(k)))
      .map(([scriptId]) => scriptId);
    if (wholeScriptDeletes.length > 0) await this.deleteRemoteScripts(wholeScriptDeletes, report);
    const handledByScriptDelete = new Set(wholeScriptDeletes);

    for (const action of plan.actions) {
      if (action.kind === "deleteRemote" && handledByScriptDelete.has(scriptIdOf(action.key))) continue;
      await this.attempt(action.key, report, () => this.apply(action, report));
    }
    for (const scriptId of new Set(plan.actions.filter((a) => a.kind === "deleteLocal").map((a) => scriptIdOf(a.key)))) {
      if (!this.remoteScripts.has(scriptId)) this.pruneEmptyDirs(path.join(this.root, scriptId));
    }
    this.saveState();
    return report;
  }

  private async apply(action: SyncAction, report: SyncReport): Promise<void> {
    const { address, token } = this.options;
    const key = action.key;
    const scriptId = scriptIdOf(key);
    const rel = relPathOf(key);
    const pull = async () => {
      const content = await readFile(address, scriptId, rel, token);
      this.writeLocal(key, content);
      this.base.set(key, sha256(content));
    };
    const push = async () => {
      if (!this.remoteScripts.has(scriptId)) await this.createRemoteScriptOnly(scriptId);
      const content = fs.readFileSync(this.localPath(key));
      await writeFile(address, scriptId, rel, content, token);
      this.base.set(key, sha256(content));
    };
    switch (action.kind) {
      case "pull":
        await pull();
        report.pulled.push(key);
        break;
      case "push":
        await push();
        report.pushed.push(key);
        break;
      case "deleteLocal":
        fs.rmSync(this.localPath(key), { force: true });
        this.pruneEmptyDirs(path.dirname(this.localPath(key)), path.join(this.root, scriptId));
        this.base.delete(key);
        report.deletedLocal.push(key);
        break;
      case "deleteRemote":
        await this.deleteRemoteFile(key);
        this.base.delete(key);
        report.deletedRemote.push(key);
        break;
      case "conflict": {
        const content = await readFile(address, scriptId, rel, token);
        this.recordConflict(key, content);
        report.conflicts.push(key);
        break;
      }
      case "restore":
        if (action.from === "remote") await pull();
        else await push();
        report.restored.push(key);
        break;
    }
  }

  private async pushIfChanged(key: string, report: SyncReport): Promise<void> {
    const sha = this.localSha(key);
    if (sha === undefined || sha === this.base.get(key)) return;
    const scriptId = scriptIdOf(key);
    const rel = relPathOf(key);
    if (this.filterFor(scriptId)(rel, false)) return;
    await this.attempt(key, report, async () => {
      await writeFile(this.options.address, scriptId, rel, fs.readFileSync(this.localPath(key)), this.options.token);
      this.base.set(key, sha);
      report.pushed.push(key);
    });
  }

  private async applyRemoteContent(key: string, content: Uint8Array, report: SyncReport): Promise<void> {
    const remoteSha = sha256(content);
    const localSha = this.localSha(key);
    if (remoteSha === localSha) {
      this.base.set(key, remoteSha);
    } else if (localSha === this.base.get(key)) {
      this.writeLocal(key, content);
      this.base.set(key, remoteSha);
      report.pulled.push(key);
    } else {
      this.recordConflict(key, content);
      report.conflicts.push(key);
    }
  }

  /**
   * 衝突以本機為準：裝置版本存成旁邊的 `.device` 檔給使用者合併，base 記成裝置版本。這樣
   * 下一次存檔或同步會把本機版本推上去，不會每次同步都卡在同一個衝突。
   */
  private recordConflict(key: string, remoteContent: Uint8Array): void {
    this.writeLocal(`${key}${CONFLICT_SUFFIX}`, remoteContent);
    this.base.set(key, sha256(remoteContent));
  }

  private applyRemoteDeletion(key: string, report: SyncReport): void {
    const localSha = this.localSha(key);
    if (localSha !== undefined && localSha === this.base.get(key)) {
      fs.rmSync(this.localPath(key), { force: true });
      this.pruneEmptyDirs(path.dirname(this.localPath(key)), path.join(this.root, scriptIdOf(key)));
      report.deletedLocal.push(key);
    } else if (localSha !== undefined) {
      // 本機改過：保留。base 拿掉後，下次完整同步會把它當成本機新增的檔案推回裝置。
      report.restored.push(key);
    }
    this.base.delete(key);
  }

  private async createRemoteScript(scriptId: string, report: SyncReport): Promise<void> {
    const reason = this.newScriptProblem(scriptId);
    if (reason) {
      report.skipped.push({ scriptId, reason });
      return;
    }
    await this.attempt(scriptId, report, async () => {
      await this.createRemoteScriptOnly(scriptId);
      for (const file of this.walkFiles(path.join(this.root, scriptId))) {
        await this.pushIfChanged(this.keyFor(file)!, report);
      }
    });
    this.saveState();
  }

  private async createRemoteScriptOnly(scriptId: string): Promise<void> {
    await createScript(this.options.address, scriptId, this.options.token);
    this.remoteScripts.add(scriptId);
  }

  private async deleteRemoteScripts(scriptIds: string[], report: SyncReport): Promise<void> {
    if (!(await this.options.confirmDeleteScripts(scriptIds))) {
      // 拿掉 base：這些腳本在下次完整同步時會被當成裝置上新增的腳本，重新拉回本機。
      for (const scriptId of scriptIds) {
        for (const key of this.baseKeysUnder(scriptId)) this.base.delete(key);
        report.skipped.push({ scriptId, reason: "本機資料夾已刪除，但選擇保留裝置上的腳本" });
      }
      return;
    }
    for (const scriptId of scriptIds) {
      await this.attempt(scriptId, report, async () => {
        await deleteScript(this.options.address, scriptId, this.options.token);
        this.remoteScripts.delete(scriptId);
        for (const key of this.baseKeysUnder(scriptId)) {
          this.base.delete(key);
          report.deletedRemote.push(key);
        }
      });
    }
  }

  private async deleteRemoteFile(key: string): Promise<void> {
    try {
      await deleteEntry(this.options.address, scriptIdOf(key), relPathOf(key), this.options.token);
    } catch (err) {
      if (!(err instanceof ScriptHttpError && err.status === 404)) throw err;
    }
  }

  private async attempt(key: string, report: SyncReport, task: () => Promise<void>): Promise<void> {
    try {
      await task();
    } catch (err) {
      report.errors.push({ key, message: (err as Error).message });
    }
  }

  private newScriptProblem(scriptId: string): string | undefined {
    if (!isValidScriptId(scriptId)) return "裝置上的腳本名稱只能是小寫英數字、「.」「_」「-」";
    if (!fs.existsSync(path.join(this.root, scriptId, "main.lua"))) return "缺少 main.lua";
    return undefined;
  }

  private filterFor(scriptId: string): SyncFilter {
    return loadSyncFilter(path.join(this.root, scriptId));
  }

  private listLocal(): Map<string, string> {
    const local = new Map<string, string>();
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(this.root, { withFileTypes: true });
    } catch {
      return local;
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
      for (const file of this.walkFiles(path.join(this.root, entry.name))) {
        const key = this.keyFor(file)!;
        local.set(key, sha256(fs.readFileSync(file)));
      }
    }
    return local;
  }

  /** [target] 本身是檔案就只回傳它；是資料夾就遞迴列出底下的檔案，略過 `.git`。 */
  private walkFiles(target: string): string[] {
    let stat: fs.Stats;
    try {
      stat = fs.statSync(target);
    } catch {
      return [];
    }
    if (stat.isFile()) return [target];
    if (!stat.isDirectory()) return [];
    const files: string[] = [];
    for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
      if (entry.name === ".git") continue;
      files.push(...this.walkFiles(path.join(target, entry.name)));
    }
    return files;
  }

  private localSha(key: string): string | undefined {
    try {
      return sha256(fs.readFileSync(this.localPath(key)));
    } catch {
      return undefined;
    }
  }

  private writeLocal(key: string, content: Uint8Array): void {
    const target = this.localPath(key);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
  }

  private baseKeysUnder(prefix: string): string[] {
    return [...this.base.keys()].filter((k) => k === prefix || k.startsWith(`${prefix}/`));
  }

  private hasBaseUnder(scriptId: string): boolean {
    return this.baseKeysUnder(scriptId).length > 0;
  }

  /** 從 [dir] 往上刪除空資料夾，直到 [stopAt]（含）或根目錄為止。 */
  private pruneEmptyDirs(dir: string, stopAt: string = dir): void {
    let current = dir;
    while (current.startsWith(this.root + path.sep) && current.length >= stopAt.length) {
      try {
        if (fs.readdirSync(current).length > 0) return;
        fs.rmdirSync(current);
      } catch {
        return;
      }
      current = path.dirname(current);
    }
  }

  private saveState(): void {
    const dir = path.join(this.root, STATE_DIR);
    fs.mkdirSync(dir, { recursive: true });
    const state: SyncState = {
      deviceId: this.options.deviceId,
      files: Object.fromEntries([...this.base.entries()].sort(([a], [b]) => a.localeCompare(b))),
    };
    fs.writeFileSync(path.join(dir, STATE_FILE), JSON.stringify(state, null, 2) + "\n");
  }
}

function groupByScript(keys: Iterable<string>): Map<string, string[]> {
  const groups = new Map<string, string[]>();
  for (const key of keys) {
    const scriptId = scriptIdOf(key);
    groups.set(scriptId, [...(groups.get(scriptId) ?? []), key]);
  }
  return groups;
}

function firstSegment(key: string): string {
  return key.split("/")[0];
}
