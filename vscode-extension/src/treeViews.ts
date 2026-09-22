import * as vscode from "vscode";
import { listScripts, type ScriptSummary } from "./scriptSync";
import type { ConnectionState } from "./workbenchConnection";

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
 * 只列裝置上的腳本——沒有 local 分支了：點一個腳本會整份 pull 到本機隱藏鏡像資料夾再
 * 掛進 workspace（見 `extension.ts` 的 `openScriptCommand`／`vscode-local-mirror-plan.md`），
 * VS Code 自己的 Explorer 接手顯示內容，這裡不用重複畫一份檔案樹。保留「Remote Scripts」這個根節點
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
