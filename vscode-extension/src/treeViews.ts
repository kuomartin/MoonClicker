import * as vscode from "vscode";
import { listScripts, type ScriptSummary } from "./scriptSync";
import type { ConnectionState } from "./workbenchConnection";
import { scriptUri } from "./moonclickerFileSystemProvider";

export class MoonClickerTreeItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly contextValue?: string
  ) {
    super(label, collapsibleState);
    if (contextValue) {
      this.contextValue = contextValue;
    }
  }
}

export class RemoteScriptItem extends MoonClickerTreeItem {
  constructor(public readonly address: string, public readonly summary: ScriptSummary) {
    super(summary.name || summary.id, vscode.TreeItemCollapsibleState.None, "remoteScript");
    this.iconPath = new vscode.ThemeIcon("cloud");
    this.description = summary.id;
    this.command = {
      command: "moonclicker.openScript",
      title: "Open Script",
      arguments: [this],
    };
  }
}

/**
 * 只列裝置上的腳本——沒有 local 分支了（見 vscode-fsprovider-plan.md D）：編輯一律透過
 * `moonclicker.openScript` 把 `moonclicker://` virtual 資料夾掛進 workspace，VS Code 自己
 * 的 Explorer 接手顯示內容，這裡不用重複畫一份檔案樹。保留「Remote Scripts」這個根節點
 * （而不是直接把腳本攤平到樹的最上層），單純是為了讓 Open Mirror／Disconnect 這些跟
 * 「整條連線」有關的動作有地方掛 context menu。
 */
export class WorkspaceTreeProvider implements vscode.TreeDataProvider<MoonClickerTreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<MoonClickerTreeItem | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private state: ConnectionState = { status: "disconnected" };
  private token?: string;

  updateState(state: ConnectionState, token?: string) {
    this.state = state;
    this.token = token;
    this.refresh();
  }

  refresh() {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: MoonClickerTreeItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: MoonClickerTreeItem): Promise<MoonClickerTreeItem[]> {
    if (!element) {
      if (this.state.status === "connected") {
        const remoteRoot = new MoonClickerTreeItem(
          `Remote Scripts (${this.state.address})`,
          vscode.TreeItemCollapsibleState.Expanded,
          "remoteRoot"
        );
        remoteRoot.iconPath = new vscode.ThemeIcon("server");
        return [remoteRoot];
      }
      const remoteRoot = new MoonClickerTreeItem(
        "Remote Scripts (Disconnected)",
        vscode.TreeItemCollapsibleState.None,
        "remoteRootDisconnected"
      );
      remoteRoot.iconPath = new vscode.ThemeIcon("server");
      return [remoteRoot];
    }

    if (element.contextValue !== "remoteRoot" || this.state.status !== "connected") return [];
    try {
      const scripts = await listScripts(this.state.address, this.token);
      return scripts.map((s) => new RemoteScriptItem(this.state.status === "connected" ? this.state.address : "", s));
    } catch {
      return [];
    }
  }
}

/** 把裝置上的腳本掛成一個 `moonclicker://` virtual workspace folder；已經開著就直接聚焦。 */
export async function openScriptAsWorkspaceFolder(address: string, summary: ScriptSummary): Promise<void> {
  const uri = scriptUri(address, summary.id);
  const existingIndex = (vscode.workspace.workspaceFolders ?? []).findIndex(
    (f) => f.uri.toString() === uri.toString()
  );
  if (existingIndex !== -1) return;

  vscode.workspace.updateWorkspaceFolders(vscode.workspace.workspaceFolders?.length ?? 0, 0, {
    uri,
    name: summary.name || summary.id,
  });
}
