import * as vscode from "vscode";
import { listScripts, type ScriptSummary } from "./scriptSync";
import type { ConnectionState } from "./workbenchConnection";

export class RemoteScriptItem extends vscode.TreeItem {
  constructor(
    public readonly address: string,
    public readonly summary: ScriptSummary,
    running: boolean,
  ) {
    super(summary.name || summary.id, vscode.TreeItemCollapsibleState.None);
    // contextValue 決定行內按鈕：沒在跑時顯示執行，在跑時顯示停止。
    this.contextValue = running ? "remoteScriptRunning" : "remoteScript";
    this.iconPath = new vscode.ThemeIcon(running ? "debug-start" : "file-code");
    this.description = running ? `${summary.id} · 執行中` : summary.id;
    this.command = {
      command: "moonclicker.openScript",
      title: "Open Script",
      arguments: [this],
    };
  }
}

class DeviceItem extends vscode.TreeItem {
  constructor(label: string, address: string) {
    super(label, vscode.TreeItemCollapsibleState.Expanded);
    this.contextValue = "device";
    this.description = address;
    this.iconPath = new vscode.ThemeIcon("device-mobile");
  }
}

type Item = DeviceItem | RemoteScriptItem;

/**
 * 已連線時：裝置節點底下直接列出裝置上的腳本。未連線時不回傳任何節點，由 package.json 的
 * `viewsWelcome` 顯示連線按鈕。檔案內容交給 VS Code 的 Explorer 顯示本機資料夾，這裡不重畫檔案樹。
 */
export class WorkspaceTreeProvider implements vscode.TreeDataProvider<Item> {
  private _onDidChangeTreeData = new vscode.EventEmitter<Item | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private state: ConnectionState = { status: "disconnected" };
  private token?: string;
  private deviceName?: string;
  private runningScriptId?: string;

  updateState(state: ConnectionState, token?: string) {
    this.state = state;
    this.token = token;
    if (state.status !== "connected") this.deviceName = undefined;
    this.refresh();
  }

  setDeviceName(name: string | undefined) {
    this.deviceName = name;
    this.refresh();
  }

  setRunningScript(scriptId: string | undefined) {
    this.runningScriptId = scriptId;
    this.refresh();
  }

  refresh() {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: Item): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: Item): Promise<Item[]> {
    if (this.state.status !== "connected") return [];
    const address = this.state.address;
    if (!element) return [new DeviceItem(this.deviceName ?? address, address)];
    if (!(element instanceof DeviceItem)) return [];
    try {
      const scripts = await listScripts(address, this.token);
      return scripts.map((s) => new RemoteScriptItem(address, s, s.id === this.runningScriptId));
    } catch {
      return [];
    }
  }
}
