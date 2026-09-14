import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { listScripts, pullScript, pushScript } from "./scriptSync";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;

export function activate(context: vscode.ExtensionContext): void {
  connection = new WorkbenchConnection();
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBarItem.show();
  renderStatusBar({ status: "disconnected" });

  connection.onDidChangeState((state) => {
    renderStatusBar(state);
    if (state.status === "error") {
      vscode.window.showErrorMessage(`ReLC: 連線到 ${state.address} 失敗——${state.message}`);
    }
  });

  context.subscriptions.push(
    statusBarItem,
    vscode.commands.registerCommand("relc.connect", connectCommand),
    vscode.commands.registerCommand("relc.disconnect", () => connection?.disconnect()),
    vscode.commands.registerCommand("relc.pull", pullCommand),
    vscode.commands.registerCommand("relc.push", pushCommand),
  );
}

export function deactivate(): void {
  connection?.disconnect();
}

async function connectCommand(): Promise<void> {
  const address = await vscode.window.showInputBox({
    prompt: "裝置的 IP:port（見裝置端 Settings > Script Workbench 的 QR code）",
    placeHolder: "192.168.1.23:8787",
    validateInput: (value) =>
      /^[^\s:]+:\d+$/.test(value) ? undefined : "格式需要是 ip:port",
  });
  if (!address) return;
  connection?.connect(address);
}

function connectedAddress(): string | undefined {
  const state = connection?.state;
  if (state?.status !== "connected") {
    vscode.window.showErrorMessage("ReLC: 尚未連線到裝置");
    return undefined;
  }
  return state.address;
}

async function pickScript(address: string): Promise<string | undefined> {
  const scripts = await listScripts(address);
  if (scripts.length === 0) {
    vscode.window.showInformationMessage("ReLC: 裝置上還沒有任何腳本");
    return undefined;
  }
  const picked = await vscode.window.showQuickPick(
    scripts.map((s) => ({ label: s.name, description: s.id, id: s.id })),
    { placeHolder: "選擇要同步的 Script Folder" },
  );
  return picked?.id;
}

async function pullCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    const id = await pickScript(address);
    if (!id) return;
    const folders = await vscode.window.showOpenDialog({
      canSelectFiles: false,
      canSelectFolders: true,
      openLabel: "Pull 到這個資料夾",
    });
    const destDir = folders?.[0]?.fsPath;
    if (!destDir) return;
    await pullScript(address, id, destDir);
    vscode.window.showInformationMessage(`ReLC: 已把「${id}」同步到 ${destDir}`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function pushCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    vscode.window.showErrorMessage("ReLC: 請先開啟要推送的專案資料夾");
    return;
  }
  const id = await vscode.window.showInputBox({
    prompt: "要推送到裝置上的哪個 Script Folder id？",
    value: workspaceFolder.name,
  });
  if (!id) return;
  try {
    await pushScript(address, id, workspaceFolder.uri.fsPath);
    vscode.window.showInformationMessage(`ReLC: 已把目前專案推送到裝置的「${id}」`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

function renderStatusBar(state: ConnectionState): void {
  if (!statusBarItem) return;
  switch (state.status) {
    case "disconnected":
      statusBarItem.text = "$(circle-slash) ReLC: 未連線";
      statusBarItem.command = "relc.connect";
      break;
    case "connecting":
      statusBarItem.text = `$(sync~spin) ReLC: 連線中 ${state.address}`;
      statusBarItem.command = "relc.disconnect";
      break;
    case "connected":
      statusBarItem.text = `$(check) ReLC: 已連線 ${state.address}`;
      statusBarItem.command = "relc.disconnect";
      break;
    case "error":
      statusBarItem.text = `$(error) ReLC: 連線失敗`;
      statusBarItem.command = "relc.connect";
      break;
  }
}
