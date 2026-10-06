import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { FolderSync, readSyncState, type SyncReport } from "./folderSync";
import { FileChangeEvent, FileChangeEvent_Kind } from "./generated/workbench_stream_event_pb";
import { mergeLuarc } from "./luarc";
import { createScript, getDeviceInfo, isValidScriptId, ScriptHttpError, type DeviceInfo } from "./scriptSync";
import { CONFLICT_SUFFIX } from "./syncIgnore";

/**
 * [FolderSync] 的 VS Code 端：決定本機資料夾、掛 watcher、把同步結果告訴使用者。
 *
 * 本機資料夾以裝置的 instance id 對應（globalState），一律由使用者選擇，不給預設路徑。
 * 資料夾不是目前 workspace 時以 `vscode.openFolder` 開啟，視窗重新載入後自動重連，
 * 再由 [start] 接手同步。
 */
export class FolderSyncController implements vscode.Disposable {
  private sync: FolderSync | undefined;
  private watcher: vscode.FileSystemWatcher | undefined;
  private readonly output = vscode.window.createOutputChannel("MoonClicker Sync");

  constructor(private readonly context: vscode.ExtensionContext) {}

  get root(): string | undefined {
    return this.sync?.root;
  }

  /**
   * 連線成功後呼叫。[interactive] 為 false（重新載入後自動重連）時不跳任何選擇，只在目前
   * workspace 已經是這台裝置的資料夾時同步，避免每個 VS Code 視窗都來問一次。
   */
  async start(address: string, token: string | undefined, interactive: boolean, onDevice: (device: DeviceInfo) => void): Promise<void> {
    this.stop();
    let device: DeviceInfo;
    try {
      device = await getDeviceInfo(address, token);
    } catch (err) {
      if (err instanceof ScriptHttpError && err.status === 404) {
        vscode.window.showWarningMessage("MoonClicker: 裝置上的 App 版本太舊，不支援同步腳本資料夾，請更新 App");
      } else {
        vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
      }
      return;
    }
    onDevice(device);
    const root = await this.resolveRoot(device, interactive);
    if (!root) return;

    try {
      this.sync = new FolderSync({
        root,
        deviceId: device.id,
        address,
        token,
        confirmDeleteScripts: (ids) => confirmDeleteScripts(ids),
      });
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
      return;
    }
    try {
      this.ensureLuarc(root);
    } catch {}

    const sync = this.sync;
    try {
      const report = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `MoonClicker: 同步「${device.name}」的腳本…` },
        () => sync.fullSync(),
      );
      this.showReport(report, true);
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: 同步失敗——${(err as Error).message}`);
      this.stop();
      return;
    }

    const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(vscode.Uri.file(root), "**/*"));
    const onChange = (uri: vscode.Uri) => this.run(sync.localChanged(uri.fsPath));
    watcher.onDidCreate(onChange);
    watcher.onDidChange(onChange);
    watcher.onDidDelete((uri) => this.run(sync.localDeleted(uri.fsPath)));
    this.watcher = watcher;
  }

  /**
   * 重新做一次完整同步。直接在手機上（App、檔案管理員）做的改動不會推送事件，要靠這個或
   * 重新連線才會同步。
   */
  async resync(): Promise<void> {
    const sync = this.sync;
    if (!sync) return;
    try {
      this.showReport(await sync.fullSync(), true);
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: 同步失敗——${(err as Error).message}`);
    }
  }

  stop(): void {
    this.watcher?.dispose();
    this.watcher = undefined;
    this.sync = undefined;
  }

  dispose(): void {
    this.stop();
    this.output.dispose();
  }

  handleRemoteChange(change: FileChangeEvent): void {
    const sync = this.sync;
    if (!sync) return;
    const kind = change.kind === FileChangeEvent_Kind.DELETED ? "deleted" : "changed";
    this.run(sync.remoteChanged(change.scriptId, change.path, kind));
  }

  scriptIdFor(fsPath: string): string | undefined {
    return this.sync?.scriptIdFor(fsPath);
  }

  mainLuaUri(scriptId: string): vscode.Uri | undefined {
    if (!this.sync) return undefined;
    const file = path.join(this.sync.root, scriptId, "main.lua");
    return fs.existsSync(file) ? vscode.Uri.file(file) : undefined;
  }

  /** 推上 [fsPath] 的最新內容，並等先前排隊的同步都做完；執行腳本前呼叫。 */
  async flush(fsPath: string): Promise<void> {
    if (this.sync) this.showReport(await this.sync.localChanged(fsPath), false);
  }

  /** 在裝置上新增腳本、拉回本機並開啟它的 main.lua。 */
  async newScript(address: string, token: string | undefined): Promise<void> {
    const sync = this.sync;
    if (!sync) {
      vscode.window.showErrorMessage("MoonClicker: 請先連線並選擇腳本資料夾");
      return;
    }
    const id = await vscode.window.showInputBox({
      prompt: "新腳本的名稱，同時是資料夾名稱與 uniqueId",
      placeHolder: "my-script",
      validateInput: (value) =>
        isValidScriptId(value) ? undefined : "只能是小寫英數字、「.」「_」「-」，且不能以「.」開頭",
    });
    if (!id) return;
    try {
      await createScript(address, id, token);
      this.showReport(await sync.remoteChanged(id, "", "changed"), false);
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
      return;
    }
    const uri = this.mainLuaUri(id);
    if (uri) await vscode.window.showTextDocument(uri);
  }

  private run(task: Promise<SyncReport>): void {
    task.then(
      (report) => this.showReport(report, false),
      (err) => vscode.window.showErrorMessage(`MoonClicker: 同步失敗——${(err as Error).message}`),
    );
  }

  private async resolveRoot(device: DeviceInfo, interactive: boolean): Promise<string | undefined> {
    const key = `moonclicker.syncFolder.${device.id}`;
    const folders = (vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath);
    const bound = folders.find((f) => readSyncState(f)?.deviceId === device.id);
    if (bound) {
      await this.context.globalState.update(key, bound);
      return bound;
    }
    const saved = this.context.globalState.get<string>(key);
    if (saved && folders.some((f) => samePath(f, saved))) return saved;
    if (!interactive) return undefined;

    if (saved && fs.existsSync(saved)) {
      const choice = await vscode.window.showInformationMessage(
        `MoonClicker:「${device.name}」的腳本資料夾是 ${saved}，要在這個視窗開啟嗎？`,
        "開啟",
        "選擇其他資料夾",
      );
      if (choice === "開啟") {
        await openFolder(saved);
        return undefined;
      }
      if (choice !== "選擇其他資料夾") return undefined;
    }

    const picked = await pickFolder(device);
    if (!picked) return undefined;
    await this.context.globalState.update(key, picked);
    if (folders.some((f) => samePath(f, picked))) return picked;
    await openFolder(picked);
    return undefined;
  }

  /** `.luarc.json` 放在資料夾根目錄，整個資料夾的腳本共用 Lua API 型別提示；不在任何腳本裡，不會同步上裝置。 */
  private ensureLuarc(root: string): void {
    const stubPath = path.join(this.context.extensionPath, "lua-meta");
    const luarcPath = path.join(root, ".luarc.json");
    let existing: Record<string, unknown> = {};
    if (fs.existsSync(luarcPath)) {
      try {
        existing = JSON.parse(fs.readFileSync(luarcPath, "utf8"));
      } catch {
        return;
      }
    }
    fs.writeFileSync(luarcPath, JSON.stringify(mergeLuarc(existing, stubPath), null, 2) + "\n");
  }

  private showReport(report: SyncReport, isFullSync: boolean): void {
    const lines = [
      ...report.pulled.map((k) => `← ${k}`),
      ...report.pushed.map((k) => `→ ${k}`),
      ...report.deletedLocal.map((k) => `✕ 本機 ${k}`),
      ...report.deletedRemote.map((k) => `✕ 裝置 ${k}`),
      ...report.conflicts.map((k) => `! 衝突 ${k}（裝置版本存為 ${k}${CONFLICT_SUFFIX}）`),
      ...report.restored.map((k) => `↺ 保留修改過的版本 ${k}`),
      ...report.skipped.map((s) => `- 略過 ${s.scriptId}：${s.reason}`),
      ...report.errors.map((e) => `✗ ${e.key}：${e.message}`),
    ];
    if (lines.length > 0) {
      this.output.appendLine(`[${new Date().toLocaleTimeString()}]`);
      for (const line of lines) this.output.appendLine(`  ${line}`);
    }

    const show = (choice: string | undefined) => {
      if (choice) this.output.show(true);
    };
    if (report.errors.length > 0) {
      vscode.window
        .showErrorMessage(`MoonClicker: ${report.errors.length} 個項目同步失敗`, "顯示詳細")
        .then(show);
    }
    if (report.conflicts.length > 0) {
      vscode.window
        .showWarningMessage(
          `MoonClicker: ${report.conflicts.length} 個檔案兩邊都改過。已保留本機版本，裝置版本另存為 ${CONFLICT_SUFFIX} 檔；下次存檔會以本機版本覆蓋裝置。`,
          "顯示詳細",
        )
        .then(show);
    }
    if (report.skipped.length > 0) {
      vscode.window
        .showWarningMessage(`MoonClicker: 略過 ${report.skipped.length} 支腳本`, "顯示詳細")
        .then(show);
    }
    if (isFullSync && report.errors.length === 0) {
      const moved = report.pulled.length + report.pushed.length + report.deletedLocal.length + report.deletedRemote.length;
      vscode.window.setStatusBarMessage(`MoonClicker: 同步完成（${moved} 個檔案有變動）`, 5000);
    }
  }
}

async function confirmDeleteScripts(scriptIds: string[]): Promise<boolean> {
  const choice = await vscode.window.showWarningMessage(
    `本機已刪除 ${scriptIds.join("、")}。要一併刪除裝置上的這些腳本嗎？`,
    { modal: true, detail: "選擇保留時，這些腳本下次同步會重新下載到本機。" },
    "刪除裝置上的腳本",
  );
  return choice === "刪除裝置上的腳本";
}

async function pickFolder(device: DeviceInfo): Promise<string | undefined> {
  const uris = await vscode.window.showOpenDialog({
    canSelectFolders: true,
    canSelectFiles: false,
    canSelectMany: false,
    openLabel: "選擇資料夾",
    title: `選擇存放「${device.name}」腳本的本機資料夾`,
  });
  const dir = uris?.[0]?.fsPath;
  if (!dir) return undefined;
  const state = readSyncState(dir);
  if (state && state.deviceId !== device.id) {
    vscode.window.showErrorMessage("MoonClicker: 這個資料夾已經和另一台裝置同步，請選擇其他資料夾");
    return undefined;
  }
  const nonEmpty = !state && fs.readdirSync(dir).some((name) => !name.startsWith("."));
  if (nonEmpty) {
    const choice = await vscode.window.showWarningMessage(
      "這個資料夾不是空的，要和裝置上的腳本合併嗎？",
      {
        modal: true,
        detail:
          "含 main.lua 的子資料夾會在裝置上建立成新腳本；與裝置同名的腳本逐檔比對，兩邊內容不同的檔案保留本機版本，裝置版本另存為 .device 檔。",
      },
      "合併",
    );
    if (choice !== "合併") return undefined;
  }
  return dir;
}

async function openFolder(dir: string): Promise<void> {
  await vscode.commands.executeCommand("vscode.openFolder", vscode.Uri.file(dir), { forceReuseWindow: true });
}

/** Windows 與 macOS 的檔案系統預設不分大小寫，同一個資料夾可能以不同大小寫出現。 */
function samePath(a: string, b: string): boolean {
  const norm = (p: string) => {
    const resolved = path.resolve(p);
    return process.platform === "win32" || process.platform === "darwin" ? resolved.toLowerCase() : resolved;
  };
  return norm(a) === norm(b);
}
