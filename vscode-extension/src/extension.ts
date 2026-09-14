import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";

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
