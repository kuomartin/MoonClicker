import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import { listScripts, type ScriptSummary } from "./scriptSync";
import type { ConnectionState } from "./workbenchConnection";

export class ReLCTreeItem extends vscode.TreeItem {
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

export class LocalScriptItem extends ReLCTreeItem {
  constructor(
    label: string,
    public readonly scriptId: string,
    public readonly scriptPath: string,
    public readonly isMonorepo: boolean
  ) {
    super(label, vscode.TreeItemCollapsibleState.None, "localScript");
    this.iconPath = new vscode.ThemeIcon("file-code");
    this.description = scriptId;
  }
}

export class RemoteScriptItem extends ReLCTreeItem {
  constructor(public readonly summary: ScriptSummary) {
    super(summary.name || summary.id, vscode.TreeItemCollapsibleState.None, "remoteScript");
    this.iconPath = new vscode.ThemeIcon("cloud");
    this.description = summary.id;
  }
}

export class WorkspaceTreeProvider implements vscode.TreeDataProvider<ReLCTreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<ReLCTreeItem | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
  
  private state: ConnectionState = { status: "disconnected" };

  updateState(state: ConnectionState) {
    this.state = state;
    this.refresh();
  }

  refresh() {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: ReLCTreeItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: ReLCTreeItem): Promise<ReLCTreeItem[]> {
    if (!element) {
      // Root level: Local and Remote categories
      const items: ReLCTreeItem[] = [];
      
      const localRoot = new ReLCTreeItem("Local Scripts", vscode.TreeItemCollapsibleState.Expanded, "localRoot");
      localRoot.iconPath = new vscode.ThemeIcon("folder-library");
      items.push(localRoot);

      if (this.state.status === "connected") {
        const remoteRoot = new ReLCTreeItem(`Remote Scripts (${this.state.address})`, vscode.TreeItemCollapsibleState.Expanded, "remoteRoot");
        remoteRoot.iconPath = new vscode.ThemeIcon("server");
        items.push(remoteRoot);
      } else {
        const remoteRoot = new ReLCTreeItem("Remote Scripts (Disconnected)", vscode.TreeItemCollapsibleState.None, "remoteRootDisconnected");
        remoteRoot.iconPath = new vscode.ThemeIcon("server");
        items.push(remoteRoot);
      }

      return items;
    }

    if (element.contextValue === "localRoot") {
      return this.getLocalScripts();
    } else if (element.contextValue === "remoteRoot") {
      return this.getRemoteScripts();
    }

    return [];
  }

  private getLocalScripts(): LocalScriptItem[] {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) return [];
    
    const root = workspaceFolders[0].uri.fsPath;
    const items: LocalScriptItem[] = [];

    if (fs.existsSync(path.join(root, "main.lua"))) {
      let scriptId = path.basename(root);
      const jsonPath = path.join(root, "script.json");
      if (fs.existsSync(jsonPath)) {
        try {
          const json = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
          if (json.id) scriptId = json.id;
        } catch {}
      }
      items.push(new LocalScriptItem(scriptId, scriptId, root, false));
      return items;
    }

    const children = fs.readdirSync(root, { withFileTypes: true });
    for (const child of children) {
      if (child.isDirectory()) {
        const dirPath = path.join(root, child.name);
        if (fs.existsSync(path.join(dirPath, "main.lua"))) {
          let scriptId = child.name;
          const jsonPath = path.join(dirPath, "script.json");
          if (fs.existsSync(jsonPath)) {
            try {
              const json = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
              if (json.id) scriptId = json.id;
            } catch {}
          }
          items.push(new LocalScriptItem(scriptId, scriptId, dirPath, true));
        }
      }
    }
    return items;
  }

  private async getRemoteScripts(): Promise<RemoteScriptItem[]> {
    if (this.state.status !== "connected") return [];
    try {
      const scripts = await listScripts(this.state.address);
      return scripts.map(s => new RemoteScriptItem(s));
    } catch {
      return [];
    }
  }
}
