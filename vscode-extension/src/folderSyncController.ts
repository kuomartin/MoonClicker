import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { FOLDER_OWNED_BY_OTHER_DEVICE, FolderSync, readSyncState, type SyncReport } from "./folderSync";
import { FileChangeEvent, FileChangeEvent_Kind } from "./generated/workbench_stream_event_pb";
import { mergeLuarc } from "./luarc";
import {
  createScript,
  getDeviceInfo,
  INVALID_SCRIPT_ID,
  isValidScriptId,
  protocolMismatch,
  ScriptHttpError,
  type DeviceInfo,
} from "./scriptSync";
import { CONFLICT_SUFFIX } from "./syncIgnore";
import { errorText, localize } from "./l10n";

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
        vscode.window.showWarningMessage(
          vscode.l10n.t("MoonClicker: The app on the device is too old to sync the scripts folder. Please update the app."),
        );
      } else {
        vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
      }
      return;
    }
    const mismatch = protocolMismatch(device);
    if (mismatch) {
      vscode.window.showErrorMessage(`MoonClicker: ${localize(mismatch)}`);
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
      vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
      return;
    }
    try {
      this.ensureLuarc(root);
    } catch {}

    const sync = this.sync;
    try {
      const report = await vscode.window.withProgress(
        {
          location: vscode.ProgressLocation.Notification,
          title: vscode.l10n.t("MoonClicker: Syncing scripts on \"{0}\"…", device.name),
        },
        () => sync.fullSync(),
      );
      this.showReport(report, true);
    } catch (err) {
      vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Sync failed: {0}", errorText(err)));
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
      vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Sync failed: {0}", errorText(err)));
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
      vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Connect and choose a scripts folder first"));
      return;
    }
    const id = await vscode.window.showInputBox({
      prompt: vscode.l10n.t("Name of the new script, used as both its folder name and uniqueId"),
      placeHolder: "my-script",
      validateInput: (value) =>
        isValidScriptId(value) ? undefined : localize(INVALID_SCRIPT_ID),
    });
    if (!id) return;
    try {
      await createScript(address, id, token);
      this.showReport(await sync.remoteChanged(id, "", "changed"), false);
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
      return;
    }
    const uri = this.mainLuaUri(id);
    if (uri) await vscode.window.showTextDocument(uri);
  }

  private run(task: Promise<SyncReport>): void {
    task.then(
      (report) => this.showReport(report, false),
      (err) => vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Sync failed: {0}", errorText(err))),
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
      const open = vscode.l10n.t("Open");
      const other = vscode.l10n.t("Choose another folder");
      const choice = await vscode.window.showInformationMessage(
        vscode.l10n.t("MoonClicker: The scripts folder for \"{0}\" is {1}. Open it in this window?", device.name, saved),
        open,
        other,
      );
      if (choice === open) {
        await openFolder(saved);
        return undefined;
      }
      if (choice !== other) return undefined;
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
      ...report.deletedLocal.map((k) => "✕ " + vscode.l10n.t("local {0}", k)),
      ...report.deletedRemote.map((k) => "✕ " + vscode.l10n.t("device {0}", k)),
      ...report.conflicts.map((k) => "! " + vscode.l10n.t("conflict {0} (device version saved as {1})", k, k + CONFLICT_SUFFIX)),
      ...report.restored.map((k) => "↺ " + vscode.l10n.t("kept the modified version of {0}", k)),
      ...report.skipped.map((s) => "- " + vscode.l10n.t("skipped {0}: {1}", s.scriptId, localize(s.reason))),
      ...report.errors.map((e) => `✗ ${e.key}: ${localize(e.message)}`),
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
        .showErrorMessage(vscode.l10n.t("MoonClicker: {0} item(s) failed to sync", report.errors.length), vscode.l10n.t("Show details"))
        .then(show);
    }
    if (report.conflicts.length > 0) {
      vscode.window
        .showWarningMessage(
          vscode.l10n.t(
            "MoonClicker: {0} file(s) changed on both sides. Kept the local version and saved the device version as a {1} file; the next save overwrites the device with the local version.",
            report.conflicts.length,
            CONFLICT_SUFFIX,
          ),
          vscode.l10n.t("Show details"),
        )
        .then(show);
    }
    if (report.skipped.length > 0) {
      vscode.window
        .showWarningMessage(vscode.l10n.t("MoonClicker: Skipped {0} script(s)", report.skipped.length), vscode.l10n.t("Show details"))
        .then(show);
    }
    if (isFullSync && report.errors.length === 0) {
      const moved = report.pulled.length + report.pushed.length + report.deletedLocal.length + report.deletedRemote.length;
      vscode.window.setStatusBarMessage(vscode.l10n.t("MoonClicker: Sync complete ({0} file(s) changed)", moved), 5000);
    }
  }
}

async function confirmDeleteScripts(scriptIds: string[]): Promise<boolean> {
  const remove = vscode.l10n.t("Delete on device");
  const choice = await vscode.window.showWarningMessage(
    vscode.l10n.t("{0} was deleted locally. Delete these scripts on the device too?", scriptIds.join(", ")),
    { modal: true, detail: vscode.l10n.t("If you keep them, they are downloaded again on the next sync.") },
    remove,
  );
  return choice === remove;
}

async function pickFolder(device: DeviceInfo): Promise<string | undefined> {
  const uris = await vscode.window.showOpenDialog({
    canSelectFolders: true,
    canSelectFiles: false,
    canSelectMany: false,
    openLabel: vscode.l10n.t("Choose folder"),
    title: vscode.l10n.t("Choose a local folder for the scripts on \"{0}\"", device.name),
  });
  const dir = uris?.[0]?.fsPath;
  if (!dir) return undefined;
  const state = readSyncState(dir);
  if (state && state.deviceId !== device.id) {
    vscode.window.showErrorMessage(`MoonClicker: ${localize(FOLDER_OWNED_BY_OTHER_DEVICE)}`);
    return undefined;
  }
  const nonEmpty = !state && fs.readdirSync(dir).some((name) => !name.startsWith("."));
  if (nonEmpty) {
    const merge = vscode.l10n.t("Merge");
    const choice = await vscode.window.showWarningMessage(
      vscode.l10n.t("This folder is not empty. Merge it with the scripts on the device?"),
      {
        modal: true,
        detail: vscode.l10n.t(
          "Subfolders containing main.lua are created as new scripts on the device. Scripts with the same name are compared file by file; where they differ, the local version is kept and the device version is saved as a .device file.",
        ),
      },
      merge,
    );
    if (choice !== merge) return undefined;
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
