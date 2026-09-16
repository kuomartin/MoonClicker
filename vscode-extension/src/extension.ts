import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { mergeLuarc } from "./luarc";
import { listScripts, pullScript, pushScript, runScript } from "./scriptSync";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, LocalScriptItem, RemoteScriptItem } from "./treeViews";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;

export function activate(context: vscode.ExtensionContext): void {
  extensionContext = context;
  connection = new WorkbenchConnection();
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBarItem.show();
  renderStatusBar({ status: "disconnected" });

  workspaceProvider = new WorkspaceTreeProvider();
  vscode.window.registerTreeDataProvider("relc.workspace", workspaceProvider);

  const watcher = vscode.workspace.createFileSystemWatcher("**/{main.lua,script.json}");
  context.subscriptions.push(
    watcher.onDidCreate(() => workspaceProvider.refresh()),
    watcher.onDidChange(() => workspaceProvider.refresh()),
    watcher.onDidDelete(() => workspaceProvider.refresh()),
    vscode.workspace.onDidChangeWorkspaceFolders(() => workspaceProvider.refresh())
  );

  const logChannel = vscode.window.createOutputChannel("ReLC Script Log");
  const dataChannel = vscode.window.createOutputChannel("ReLC Script Data");

  connection.onDidChangeState((state) => {
    renderStatusBar(state);
    workspaceProvider.updateState(state);
    if (state.status === "error") {
      vscode.window.showErrorMessage(`ReLC: 連線到 ${state.address} 失敗——${state.message}`);
    }
  });

  connection.onDidReceiveStreamEvent((event) => {
    if (event.event.case === "log") {
      logChannel.appendLine(event.event.value);
    } else if (event.event.case === "data") {
      dataChannel.clear();
      dataChannel.appendLine(JSON.stringify(event.event.value, null, 2));
    }
    postStreamEventToMirror(event.event);
  });

  context.subscriptions.push(
    statusBarItem,
    logChannel,
    dataChannel,
    watcher,
    vscode.commands.registerCommand("relc.connect", connectCommand),
    vscode.commands.registerCommand("relc.disconnect", () => connection?.disconnect()),
    vscode.commands.registerCommand("relc.pull", pullCommand),
    vscode.commands.registerCommand("relc.pullRemote", pullRemoteCommand),
    vscode.commands.registerCommand("relc.push", pushCommand),
    vscode.commands.registerCommand("relc.pushAndRun", pushAndRunCommand),
    vscode.commands.registerCommand("relc.run", runCommand),
    vscode.commands.registerCommand("relc.runRemote", runRemoteCommand),
    vscode.commands.registerCommand("relc.openMirror", openMirrorCommand),
    vscode.commands.registerCommand("relc.renameScript", renameScriptCommand),
    vscode.commands.registerCommand("relc.setupStubs", setupStubsCommand),
  );
}

export function deactivate(): void {
  connection?.disconnect();
  disposeMirrorPanel();
}

function insertRunDivider(): void {
  const divider = `--- Run triggered at ${new Date().toLocaleTimeString()} ---`;
  // logChannel is local to activate(), let's just use postStreamEventToMirror for UI
  postStreamEventToMirror({ case: "log", value: divider });
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
    ensureLuarcConfigured(destDir);
    vscode.window.showInformationMessage(`ReLC: 已把「${id}」同步到 ${destDir}`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function pullRemoteCommand(item?: RemoteScriptItem): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    const id = item?.summary.id || await pickScript(address);
    if (!id) return;
    const folders = await vscode.window.showOpenDialog({
      canSelectFiles: false,
      canSelectFolders: true,
      openLabel: "Pull 到這個資料夾",
    });
    const destDir = folders?.[0]?.fsPath;
    if (!destDir) return;
    await pullScript(address, id, destDir);
    ensureLuarcConfigured(destDir);
    vscode.window.showInformationMessage(`ReLC: 已把「${id}」同步到 ${destDir}`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function getLocalScriptTarget(item?: LocalScriptItem): Promise<{ id: string, path: string } | undefined> {
  if (item) {
    return { id: item.scriptId, path: item.scriptPath };
  }
  
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    vscode.window.showErrorMessage("ReLC: 請先開啟專案資料夾");
    return;
  }
  const root = workspaceFolder.uri.fsPath;
  
  if (fs.existsSync(path.join(root, "main.lua"))) {
    const id = await ensureScriptJson(root, path.basename(root));
    return { id, path: root };
  }

  // Pick from monorepo
  const children = fs.readdirSync(root, { withFileTypes: true });
  const options = children
    .filter(c => c.isDirectory() && fs.existsSync(path.join(root, c.name, "main.lua")))
    .map(c => ({ label: c.name, path: path.join(root, c.name) }));
    
  if (options.length === 0) {
    vscode.window.showErrorMessage("ReLC: 在工作區找不到任何包含 main.lua 的資料夾");
    return;
  }
  
  const picked = await vscode.window.showQuickPick(options, { placeHolder: "選擇要操作的 Script Folder" });
  if (picked) {
    const id = await ensureScriptJson(picked.path, picked.label);
    return { id, path: picked.path };
  }
  return undefined;
}

async function ensureScriptJson(folderPath: string, folderName: string): Promise<string> {
  const jsonPath = path.join(folderPath, "script.json");
  if (!fs.existsSync(jsonPath)) {
    fs.writeFileSync(jsonPath, JSON.stringify({ id: folderName, name: folderName }, null, 2) + "\n");
    return folderName;
  }
  try {
    const json = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
    if (json.id) return json.id;
  } catch {}
  return folderName;
}

async function pushCommand(item?: LocalScriptItem): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  const target = await getLocalScriptTarget(item);
  if (!target) return;
  
  try {
    const id = await ensureScriptJson(target.path, target.id);
    await pushScript(address, id, target.path);
    vscode.window.showInformationMessage(`ReLC: 已把目前專案推送到裝置的「${id}」`);
    workspaceProvider.refresh();
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function pushAndRunCommand(item?: LocalScriptItem): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  const target = await getLocalScriptTarget(item);
  if (!target) return;
  
  try {
    const id = await ensureScriptJson(target.path, target.id);
    await pushScript(address, id, target.path);
    insertRunDivider();
    await runScript(address, id);
    vscode.window.showInformationMessage(`ReLC: 已推送並執行「${id}」`);
    workspaceProvider.refresh();
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function runCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  const target = await getLocalScriptTarget();
  if (!target) return;
  
  try {
    insertRunDivider();
    await runScript(address, target.id);
    vscode.window.showInformationMessage(`ReLC: 已在裝置上觸發「${target.id}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

async function renameScriptCommand(item?: LocalScriptItem): Promise<void> {
  const target = await getLocalScriptTarget(item);
  if (!target) return;
  
  const newName = await vscode.window.showInputBox({
    prompt: "輸入新的腳本名稱 (將同時重新命名資料夾與 script.json)",
    value: target.id
  });
  if (!newName || newName === target.id) return;
  
  const parentDir = path.dirname(target.path);
  const newPath = path.join(parentDir, newName);
  
  if (fs.existsSync(newPath)) {
    vscode.window.showErrorMessage(`ReLC: 已經存在名為「${newName}」的資料夾。`);
    return;
  }
  
  try {
    const jsonPath = path.join(target.path, "script.json");
    if (fs.existsSync(jsonPath)) {
      const json = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
      json.id = newName;
      if (json.name === target.id) {
        json.name = newName;
      }
      fs.writeFileSync(jsonPath, JSON.stringify(json, null, 2) + "\n");
    }
    
    fs.renameSync(target.path, newPath);
    vscode.window.showInformationMessage(`ReLC: 腳本已重新命名為「${newName}」`);
    workspaceProvider.refresh();
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: 重新命名失敗 - ${(err as Error).message}`);
  }
}

async function setupStubsCommand(item?: LocalScriptItem): Promise<void> {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    vscode.window.showErrorMessage("ReLC: 請先開啟專案資料夾");
    return;
  }
  
  try {
    const rootPath = workspaceFolder.uri.fsPath;
    ensureLuarcConfigured(rootPath);
    vscode.window.showInformationMessage(`ReLC: 已在工作區根目錄設定 Lua API 提示 (.luarc.json)`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: 設定 Lua 提示失敗 - ${(err as Error).message}`);
  }
}

async function runRemoteCommand(item?: RemoteScriptItem): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    const id = item?.summary.id || await pickScript(address);
    if (!id) return;
    insertRunDivider();
    await runScript(address, id);
    vscode.window.showInformationMessage(`ReLC: 已在裝置上觸發「${id}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: ${(err as Error).message}`);
  }
}

function ensureLuarcConfigured(projectDir: string): void {
  if (!extensionContext) return;
  const stubPath = path.join(extensionContext.extensionPath, "lua-meta");
  const luarcPath = path.join(projectDir, ".luarc.json");

  let existing: Record<string, unknown> = {};
  if (fs.existsSync(luarcPath)) {
    try {
      existing = JSON.parse(fs.readFileSync(luarcPath, "utf8"));
    } catch {
      return;
    }
  }

  const merged = mergeLuarc(existing, stubPath);
  fs.writeFileSync(luarcPath, JSON.stringify(merged, null, 2) + "\n");
}

async function openMirrorCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  
  try {
    const response = await fetch(`http://${address}/displays`);
    if (!response.ok) throw new Error("Could not fetch displays");
    const displays = await response.json() as any[];
    
    if (displays.length === 0) {
      vscode.window.showErrorMessage("ReLC: 裝置上沒有可用的 display");
      return;
    }
    
    let displayId = 0;
    if (displays.length > 1) {
      const picked = await vscode.window.showQuickPick(
        displays.map(d => ({ label: d.name, description: `${d.width}x${d.height}`, displayId: d.id })),
        { placeHolder: "選擇要鏡像的 display" }
      );
      if (!picked) return;
      displayId = picked.displayId;
    } else {
      displayId = displays[0].id;
    }
    if (extensionContext) {
      openMirrorPanel(extensionContext.extensionUri, address, displayId);
    }
  } catch (err) {
    // Fallback to manual entry if /displays fails
    const input = await vscode.window.showInputBox({
      prompt: "要鏡像哪個 displayId？",
      value: "0",
      validateInput: (value) => (/^\d+$/.test(value) ? undefined : "displayId 需要是非負整數"),
    });
    if (input === undefined || !extensionContext) return;
    openMirrorPanel(extensionContext.extensionUri, address, Number(input));
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
      statusBarItem.command = undefined; // Don't disconnect on click when connecting
      break;
    case "connected":
      statusBarItem.text = `$(check) ReLC: 已連線 ${state.address}`;
      statusBarItem.command = undefined; // Don't disconnect on click when connected
      break;
    case "error":
      statusBarItem.text = `$(error) ReLC: 連線失敗`;
      statusBarItem.command = "relc.connect";
      break;
  }
}
