import * as vscode from "vscode";
import * as fs from "node:fs";
import * as path from "node:path";
import { listScripts, type ScriptSummary } from "./scriptSync";
import type { ConnectionState } from "./workbenchConnection";

export class DeviceConnectionProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
  private state: ConnectionState = { status: "disconnected" };

  updateState(state: ConnectionState) {
    this.state = state;
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: vscode.TreeItem): vscode.ProviderResult<vscode.TreeItem[]> {
    if (element) return [];
    const item = new vscode.TreeItem(
      this.state.status === "connected" ? `Connected: ${this.state.address}` : 
      this.state.status === "connecting" ? `Connecting: ${this.state.address}` : 
      this.state.status === "error" ? `Error: ${this.state.message}` : "Disconnected",
      vscode.TreeItemCollapsibleState.None
    );
    item.iconPath = new vscode.ThemeIcon(
      this.state.status === "connected" ? "check" :
      this.state.status === "connecting" ? "sync~spin" :
      this.state.status === "error" ? "error" : "circle-slash"
    );
    return [item];
  }
}

export class LocalScriptItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly scriptId: string,
    public readonly scriptPath: string,
    public readonly isMonorepo: boolean
  ) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.contextValue = "localScript";
    this.iconPath = new vscode.ThemeIcon("file-code");
    this.description = scriptId;
  }
}

export class LocalScriptsProvider implements vscode.TreeDataProvider<LocalScriptItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  refresh() {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: LocalScriptItem): vscode.TreeItem {
    return element;
  }

  getChildren(element?: LocalScriptItem): vscode.ProviderResult<LocalScriptItem[]> {
    if (element) return [];
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) return [];
    
    const root = workspaceFolders[0].uri.fsPath;
    const items: LocalScriptItem[] = [];

    // Check Single-Script mode
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

    // Check Monorepo mode
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
}

export class RemoteScriptItem extends vscode.TreeItem {
  constructor(public readonly summary: ScriptSummary) {
    super(summary.name || summary.id, vscode.TreeItemCollapsibleState.None);
    this.contextValue = "remoteScript";
    this.iconPath = new vscode.ThemeIcon("cloud");
    this.description = summary.id;
  }
}

export class RemoteScriptsProvider implements vscode.TreeDataProvider<RemoteScriptItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
  private address: string | undefined;

  setAddress(address: string | undefined) {
    this.address = address;
    this.refresh();
  }

  refresh() {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: RemoteScriptItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: RemoteScriptItem): Promise<RemoteScriptItem[]> {
    if (element || !this.address) return [];
    try {
      const scripts = await listScripts(this.address);
      return scripts.map(s => new RemoteScriptItem(s));
    } catch {
      return [];
    }
  }
}
