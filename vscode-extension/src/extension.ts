import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { mergeLuarc } from "./luarc";
import { listDisplays, toggleDisplayMirror, type DisplaySummary } from "./displaySync";
import { listScripts, pullScript, pushScript, runScript } from "./scriptSync";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, LocalScriptItem, RemoteScriptItem } from "./treeViews";
import { startMdnsDaemon, getCachedDevices, mdnsEvents, checkDeviceHealth, refreshMdns } from "./mdnsDiscovery";
import { pairWithPin } from "./authSync";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;
let activeToken: string | undefined;

export function activate(context: vscode.ExtensionContext): void {
  startMdnsDaemon();
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
    workspaceProvider.updateState(state, activeToken);
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
    vscode.commands.registerCommand("relc.toggleMirror", toggleMirrorCommand),
  );
}

export function deactivate(): void {
  connection?.disconnect();
  disposeMirrorPanel();
}

function insertRunDivider(): void {
  const divider = `--- Run triggered at ${new Date().toLocaleTimeString()} ---`;
  postStreamEventToMirror({ case: "log", value: divider });
}

interface ConnectItem extends vscode.QuickPickItem {
  address?: string;
  isManual?: boolean;
}

async function connectCommand(): Promise<void> {
  if (!extensionContext) return;

  const quickPick = vscode.window.createQuickPick<ConnectItem>();
  quickPick.title = "ReLC: 搜尋區網內的裝置...";
  quickPick.placeholder = "選擇要連線的 ReLC 裝置";
  quickPick.busy = true;

  const baseItems: ConnectItem[] = [
    {
      label: "$(edit) 手動輸入 IP:port",
      description: "自行輸入裝置位址",
      isManual: true,
    }
  ];

  const updateItems = async () => {
    const devices = getCachedDevices();
    let currentItems: ConnectItem[] = [...baseItems];
    
    // Optimistic render
    devices.forEach(dev => {
      currentItems.push({
        label: `$(circle-outline) ${dev.name}`,
        description: dev.address,
        detail: "Checking status...",
        address: dev.address,
      });
    });
    quickPick.items = currentItems;

    // Async health checks
    for (const dev of devices) {
      checkDeviceHealth(dev.ip, dev.port).then((isOnline) => {
        // Update specific item
        const items = [...quickPick.items];
        const idx = items.findIndex(i => i.address === dev.address);
        if (idx !== -1) {
          items[idx] = {
            ...items[idx],
            label: isOnline ? `$(pass-filled) ${dev.name}` : `$(circle-outline) ${dev.name}`,
            detail: isOnline ? "mDNS 自動發現的裝置" : "(Offline / Cached)",
          };
          quickPick.items = items;
        }
      });
    }
  };

  const listener = () => updateItems();
  mdnsEvents.on("deviceAdded", listener);

  quickPick.onDidHide(() => {
    mdnsEvents.off("deviceAdded", listener);
    quickPick.dispose();
  });

  quickPick.onDidAccept(async () => {
    const picked = quickPick.selectedItems[0];
    if (!picked) return;
    
    quickPick.hide();
    
    let address: string | undefined;
    if (picked.isManual) {
      address = await vscode.window.showInputBox({
        prompt: "裝置的 IP:port（見裝置端 Settings > Script Workbench）",
        placeHolder: "192.168.1.23:8787",
        validateInput: (value) =>
          /^[^\s:]+:\d+$/.test(value) ? undefined : "格式需要是 ip:port",
      });
    } else {
      address = picked.address;
    }

    if (!address) return;

    const secretKey = `relc_token_${address}`;
    let token = await extensionContext!.secrets.get(secretKey);

    let authenticated = false;
    if (token) {
      try {
        await listDisplays(address, token);
        authenticated = true;
      } catch {
        authenticated = false;
      }
    }

    if (!authenticated) {
      const pin = await vscode.window.showInputBox({
        prompt: `連線至 ${address} 需要驗證，請輸入 Android 裝置畫面上顯示的 6 位數 PIN 碼（若未開啟，請先在手機端開啟配對模式）`,
        placeHolder: "123456",
        password: true,
        validateInput: (val) => (/^\d{6}$/.test(val) ? undefined : "PIN 碼格式需要是 6 位數字"),
      });

      if (!pin) return;

      try {
        token = await pairWithPin(address, pin);
        await extensionContext!.secrets.store(secretKey, token);
        vscode.window.showInformationMessage(`ReLC: 連線至 ${address} 配對成功！已儲存憑證。`);
      } catch (err) {
        vscode.window.showErrorMessage(`ReLC 配對失敗: ${(err as Error).message}`);
        return;
      }
    }

    activeToken = token;
    connection?.connect(address, token);
  });

  updateItems();
  refreshMdns();
  quickPick.show();
  
  setTimeout(() => { quickPick.busy = false; }, 2000);
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
  const scripts = await listScripts(address, activeToken);
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
    await pullScript(address, id, destDir, activeToken);
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
    await pullScript(address, id, destDir, activeToken);
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
    await pushScript(address, id, target.path, activeToken);
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
    await pushScript(address, id, target.path, activeToken);
    insertRunDivider();
    await runScript(address, id, activeToken);
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
    await runScript(address, target.id, activeToken);
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

async function setupStubsCommand(): Promise<void> {
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
    await runScript(address, id, activeToken);
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
    const displays = await listDisplays(address, activeToken);
    
    if (displays.length === 0) {
      vscode.window.showErrorMessage("ReLC: 裝置上沒有可用的 display");
      return;
    }
    
    let displayId = 0;
    let pickedDisplay: DisplaySummary = displays[0];
    if (displays.length > 1) {
      const items = displays.map(d => {
        let desc = `${d.width}x${d.height}`;
        if (d.isVirtual === false) {
          desc += d.isMirrorActive ? " (實體螢幕 · 鏡像中)" : " (實體螢幕 · 鏡像未啟動)";
        }
        return {
          label: d.name,
          description: desc,
          display: d,
        };
      });
      const picked = await vscode.window.showQuickPick(items, {
        placeHolder: "選擇要鏡像的 display",
      });
      if (!picked) return;
      displayId = picked.display.id;
      pickedDisplay = picked.display;
    } else {
      displayId = displays[0].id;
      pickedDisplay = displays[0];
    }

    if (pickedDisplay.isVirtual === false && !pickedDisplay.isMirrorActive) {
      const choice = await vscode.window.showWarningMessage(
        `實體螢幕 (Display ${displayId}) 尚未啟動鏡像管線。是否立即啟動？`,
        "啟動鏡像",
        "直接開啟面板"
      );
      if (!choice) return;
      if (choice === "啟動鏡像") {
        try {
          await toggleDisplayMirror(address, displayId, true, activeToken);
          vscode.window.showInformationMessage(`ReLC: 實體螢幕 (Display ${displayId}) 鏡像管線已啟動`);
        } catch (e) {
          vscode.window.showErrorMessage(`ReLC: 啟動鏡像失敗: ${(e as Error).message}`);
        }
      }
    }
    if (extensionContext) {
      openMirrorPanel(extensionContext.extensionUri, address, displayId, activeToken);
    }
  } catch (err) {
    const input = await vscode.window.showInputBox({
      prompt: "要鏡像哪個 displayId？",
      value: "0",
      validateInput: (value) => (/^\d+$/.test(value) ? undefined : "displayId 需要是非負整數"),
    });
    if (input === undefined || !extensionContext) return;
    openMirrorPanel(extensionContext.extensionUri, address, Number(input), activeToken);
  }
}

async function toggleMirrorCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;

  try {
    const displays = await listDisplays(address, activeToken);
    const target = displays.find(d => !d.isVirtual) || displays[0];
    if (!target) {
      vscode.window.showErrorMessage("ReLC: 裝置上沒有找到顯示器");
      return;
    }

    let targetDisplay = target;
    if (displays.length > 1) {
      const items = displays.map(d => ({
        label: d.name,
        description: `${d.width}x${d.height} [${d.isVirtual ? "虛擬" : (d.isMirrorActive ? "實體·鏡像中" : "實體·未啟動")}]`,
        display: d,
      }));
      const picked = await vscode.window.showQuickPick(items, {
        placeHolder: "選擇要切換鏡像狀態的顯示器",
      });
      if (!picked) return;
      targetDisplay = picked.display;
    }

    const nextState = !targetDisplay.isMirrorActive;
    await toggleDisplayMirror(address, targetDisplay.id, nextState, activeToken);
    vscode.window.showInformationMessage(
      `ReLC: 顯示器 ${targetDisplay.id} (${targetDisplay.name}) 鏡像已${nextState ? "開啟" : "關閉"}`
    );
  } catch (err) {
    vscode.window.showErrorMessage(`ReLC: 切換鏡像失敗 - ${(err as Error).message}`);
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
      statusBarItem.command = undefined;
      break;
    case "connected":
      statusBarItem.text = `$(check) ReLC: 已連線 ${state.address}`;
      statusBarItem.command = undefined;
      break;
    case "error":
      statusBarItem.text = `$(error) ReLC: 連線失敗`;
      statusBarItem.command = "relc.connect";
      break;
  }
}
