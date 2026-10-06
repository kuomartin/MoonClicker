import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { listDisplays } from "./displaySync";
import { listScripts, runScript } from "./scriptSync";
import { FolderSyncController } from "./folderSyncController";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, RemoteScriptItem } from "./treeViews";
import { startMdnsDaemon, getCachedDevices, mdnsEvents, checkDeviceHealth, refreshMdns } from "./mdnsDiscovery";
import { pairWithPin } from "./authSync";
import { stopRun } from "./visionTest";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
/** 腳本執行中才出現，點擊即停止。 */
let runStatusItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;
let activeToken: string | undefined;
let folderSync: FolderSyncController;
/** 使用者按下 Connect 之後的第一次連上才算互動式：可以跳出選擇資料夾。自動重連不跳。 */
let interactiveConnect = false;
/** 每個 address 各自的 token：自動重連時從 secrets 撈回來。 */
const tokensByAddress = new Map<string, string>();

/** 記錄「使用者上次手動連線的裝置」，讓 [reconnectLastDevice] 在視窗重新載入（例如開啟腳本資料夾）
 *  之後把連線接回去。 */
function rememberLastAddress(address: string): void {
  extensionContext?.globalState.update("moonclicker.lastAddress", address);
}

function forgetLastAddress(): void {
  extensionContext?.globalState.update("moonclicker.lastAddress", undefined);
}

/** 開啟腳本資料夾會重新載入視窗，[connection] 變成全新的 disconnected 實例；這裡用
 *  [rememberLastAddress] 記下的位址自動接回去。 */
async function reconnectLastDevice(): Promise<void> {
  if (!extensionContext || !connection) return;
  const address = extensionContext.globalState.get<string>("moonclicker.lastAddress");
  if (!address) return;
  const state = connection.state;
  if (state.status === "connected" || state.status === "connecting") return;
  let token = tokensByAddress.get(address);
  if (!token) {
    token = await extensionContext.secrets.get(`moonclicker_token_${address}`);
    if (token) tokensByAddress.set(address, token);
  }
  activeToken = token;
  connection.connect(address, token);
}

export function activate(context: vscode.ExtensionContext): void {
  startMdnsDaemon();
  extensionContext = context;
  connection = new WorkbenchConnection();
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBarItem.show();
  renderStatusBar({ status: "disconnected" });
  runStatusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 99);
  runStatusItem.command = "moonclicker.stop";
  runStatusItem.tooltip = "停止裝置上的腳本";
  context.subscriptions.push(runStatusItem);

  workspaceProvider = new WorkspaceTreeProvider();
  vscode.window.registerTreeDataProvider("moonclicker.workspace", workspaceProvider);
  folderSync = new FolderSyncController(context);
  context.subscriptions.push(folderSync);

  removeLegacyMirrorFolders(context);
  reconnectLastDevice();

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      workspaceProvider.refresh();
    })
  );

  const logChannel = vscode.window.createOutputChannel("MoonClicker Script Log");
  const dataChannel = vscode.window.createOutputChannel("MoonClicker Script Data");

  connection.onDidChangeState((state) => {
    renderStatusBar(state);
    vscode.commands.executeCommand("setContext", "moonclicker.connected", state.status === "connected");
    workspaceProvider.updateState(state, activeToken);
    if (state.status === "connected") {
      const interactive = interactiveConnect;
      interactiveConnect = false;
      folderSync
        .start(state.address, activeToken, interactive, (device) => workspaceProvider.setDeviceName(device.name))
        .then(() => workspaceProvider.refresh());
    } else if (state.status !== "connecting") {
      folderSync.stop();
      setRunningScript(undefined);
    }
    if (state.status === "error") {
      vscode.window.showErrorMessage(`MoonClicker: 連線到 ${state.address} 失敗——${state.message}`);
    }
  });

  connection.onDidReceiveStreamEvent((event) => {
    if (event.event.case === "log") {
      logChannel.appendLine(event.event.value);
    } else if (event.event.case === "data") {
      dataChannel.clear();
      dataChannel.appendLine(JSON.stringify(event.event.value, null, 2));
    } else if (event.event.case === "runState") {
      setRunningScript(event.event.value.scriptId || undefined);
    } else if (event.event.case === "fileChange") {
      folderSync.handleRemoteChange(event.event.value);
      // 整支腳本新增／刪除，或名稱可能改了，側邊欄的清單要跟著更新。
      const changedPath = event.event.value.path;
      if (!changedPath || changedPath === "main.lua" || changedPath === "script.json") workspaceProvider.refresh();
    }
    postStreamEventToMirror(event.event);
  });

  context.subscriptions.push(
    statusBarItem,
    logChannel,
    dataChannel,
    vscode.commands.registerCommand("moonclicker.connect", connectCommand),
    vscode.commands.registerCommand("moonclicker.disconnect", () => {
      forgetLastAddress();
      connection?.disconnect();
    }),
    vscode.commands.registerCommand("moonclicker.openScript", openScriptCommand),
    vscode.commands.registerCommand("moonclicker.run", runCommand),
    vscode.commands.registerCommand("moonclicker.runRemote", runRemoteCommand),
    vscode.commands.registerCommand("moonclicker.stop", stopCommand),
    vscode.commands.registerCommand("moonclicker.newScript", () => {
      const address = connectedAddress();
      if (address) folderSync.newScript(address, activeToken);
    }),
    vscode.commands.registerCommand("moonclicker.openWorkbench", openWorkbenchCommand),
    vscode.commands.registerCommand("moonclicker.refresh", () => {
      workspaceProvider.refresh();
      folderSync.resync();
    }),
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
  quickPick.title = "MoonClicker: 搜尋區網內的裝置...";
  quickPick.placeholder = "選擇要連線的 MoonClicker 裝置";
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

    const secretKey = `moonclicker_token_${address}`;
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
        vscode.window.showInformationMessage(`MoonClicker: 連線至 ${address} 配對成功！已儲存憑證。`);
      } catch (err) {
        vscode.window.showErrorMessage(`MoonClicker 配對失敗: ${(err as Error).message}`);
        return;
      }
    }

    activeToken = token;
    if (token) tokensByAddress.set(address, token);
    rememberLastAddress(address);
    interactiveConnect = true;
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
    vscode.window.showErrorMessage("MoonClicker: 尚未連線到裝置");
    return undefined;
  }
  return state.address;
}

async function pickScript(address: string): Promise<string | undefined> {
  const scripts = await listScripts(address, activeToken);
  if (scripts.length === 0) {
    vscode.window.showInformationMessage("MoonClicker: 裝置上還沒有任何腳本");
    return undefined;
  }
  const picked = await vscode.window.showQuickPick(
    scripts.map((s) => ({ label: s.name, description: s.id, id: s.id })),
    { placeHolder: "選擇要同步的 Script Folder" },
  );
  return picked?.id;
}

/** 開啟腳本在本機資料夾裡的 main.lua；沒帶 item 時先選腳本。 */
async function openScriptCommand(item?: RemoteScriptItem): Promise<void> {
  const scriptId = item?.summary.id ?? (connectedAddress() ? await pickScript(connectedAddress()!) : undefined);
  if (!scriptId) return;
  const uri = folderSync.mainLuaUri(scriptId);
  if (!uri) {
    vscode.window.showErrorMessage(
      folderSync.root ? `MoonClicker: 本機資料夾裡沒有「${scriptId}」的 main.lua` : "MoonClicker: 請先連線並選擇腳本資料夾",
    );
    return;
  }
  await vscode.window.showTextDocument(uri);
}

/** F5：存檔、等同步完成，再在裝置上執行目前編輯器所在的腳本。 */
async function runCommand(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  const scriptId = editor ? folderSync.scriptIdFor(editor.document.uri.fsPath) : undefined;
  const address = connectedAddress();
  if (!address) return;
  if (!editor || !scriptId) {
    vscode.window.showErrorMessage("MoonClicker: 請在同步的腳本資料夾中開啟要執行的腳本");
    return;
  }
  try {
    if (editor.document.isDirty) await editor.document.save();
    await folderSync.flush(editor.document.uri.fsPath);
    insertRunDivider();
    await runScript(address, scriptId, activeToken);
    vscode.window.showInformationMessage(`MoonClicker: 已在裝置上觸發「${scriptId}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
  }
}

/**
 * 舊版把每支腳本各自鏡像到 globalStorage 並加成 workspace folder；改成同步整個資料夾後，
 * 這些資料夾留在 workspace 裡只會造成混淆。內容早已同步在裝置上，這裡只把它們移出
 * workspace，不刪除檔案。
 */
function removeLegacyMirrorFolders(context: vscode.ExtensionContext): void {
  const mirrorsRoot = path.join(context.globalStorageUri.fsPath, "mirrors") + path.sep;
  const folders = vscode.workspace.workspaceFolders ?? [];
  const kept = folders.filter((f) => !f.uri.fsPath.startsWith(mirrorsRoot));
  context.globalState.update("moonclicker.mirrors", undefined);
  if (kept.length === folders.length) return;
  vscode.workspace.updateWorkspaceFolders(0, folders.length, ...kept.map((f) => ({ uri: f.uri, name: f.name })));
}

async function runRemoteCommand(item?: RemoteScriptItem): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    const id = item?.summary.id || await pickScript(address);
    if (!id) return;
    const mainLua = folderSync.mainLuaUri(id);
    if (mainLua) await folderSync.flush(mainLua.fsPath);
    insertRunDivider();
    await runScript(address, id, activeToken);
    vscode.window.showInformationMessage(`MoonClicker: 已在裝置上觸發「${id}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
  }
}

function setRunningScript(scriptId: string | undefined): void {
  workspaceProvider.setRunningScript(scriptId);
  if (!runStatusItem) return;
  if (scriptId) {
    runStatusItem.text = `$(debug-stop) 執行中：${scriptId}`;
    runStatusItem.show();
  } else {
    runStatusItem.hide();
  }
}

const LAST_DISPLAY_KEY = "moonclicker.lastDisplayId";

/**
 * 不先問要開哪個 display：面板內本來就能切換。優先用上次在面板看的 display，其次是第一個
 * 虛擬顯示器（腳本通常跑在那裡），都沒有才是 display 0。實體螢幕還沒啟動鏡像時，面板
 * 自己會在畫面上顯示啟動按鈕。
 */
async function openWorkbenchCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address || !extensionContext) return;
  let displayId = 0;
  try {
    const displays = await listDisplays(address, activeToken);
    const last = extensionContext.globalState.get<number>(LAST_DISPLAY_KEY);
    displayId =
      displays.find((d) => d.id === last)?.id ??
      displays.find((d) => d.isVirtual)?.id ??
      displays[0]?.id ??
      0;
  } catch (err) {
    vscode.window.showWarningMessage(`MoonClicker: 讀不到顯示器清單，先開啟 display 0——${(err as Error).message}`);
  }
  openMirrorPanel(extensionContext.extensionUri, address, displayId, activeToken, (id) => {
    extensionContext?.globalState.update(LAST_DISPLAY_KEY, id);
  });
}

/** 停止裝置上正在執行的腳本；沒有腳本在跑時裝置端是 no-op。 */
async function stopCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    await stopRun(address, activeToken);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
  }
}

function renderStatusBar(state: ConnectionState): void {
  if (!statusBarItem) return;
  switch (state.status) {
    case "disconnected":
      statusBarItem.text = "$(circle-slash) MoonClicker: 未連線";
      statusBarItem.tooltip = "連線到裝置";
      statusBarItem.command = "moonclicker.connect";
      break;
    case "connecting":
      statusBarItem.text = `$(sync~spin) MoonClicker: 連線中 ${state.address}`;
      statusBarItem.tooltip = undefined;
      statusBarItem.command = undefined;
      break;
    case "connected":
      statusBarItem.text = `$(check) MoonClicker: 已連線 ${state.address}`;
      statusBarItem.tooltip = "開啟 Workbench 面板";
      statusBarItem.command = "moonclicker.openWorkbench";
      break;
    case "error":
      statusBarItem.text = `$(error) MoonClicker: 連線失敗`;
      statusBarItem.tooltip = "重新連線";
      statusBarItem.command = "moonclicker.connect";
      break;
  }
}
