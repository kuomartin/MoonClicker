import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { mergeLuarc } from "./luarc";
import { listDisplays, toggleDisplayMirror, type DisplaySummary } from "./displaySync";
import { listScripts, runScript } from "./scriptSync";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, RemoteScriptItem, openScriptAsWorkspaceFolder } from "./treeViews";
import { MoonclickerFileSystemProvider, parseUri, SCHEME } from "./moonclickerFileSystemProvider";
import { startMdnsDaemon, getCachedDevices, mdnsEvents, checkDeviceHealth, refreshMdns } from "./mdnsDiscovery";
import { pairWithPin } from "./authSync";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;
let fileSystemProvider: MoonclickerFileSystemProvider;
let activeToken: string | undefined;
/** 一個 provider 實例可能同時服務好幾個已開啟的 virtual workspace folder——可能來自不同
 *  裝置——所以每個 address 各自記自己的 token，不能只靠 [activeToken] 這個「目前連線」。 */
const tokensByAddress = new Map<string, string>();

/**
 * `vscode.workspace.updateWorkspaceFolders` 官方文件明講：第一次加入 workspace folder、或
 * 從空/單一資料夾轉成多資料夾時，擴充套件會被終止重啟——`tokensByAddress`／`activeToken`／
 * `connection` 這些純記憶體狀態全部歸零，但已經掛上的 `moonclicker:` virtual 資料夾會被
 * VS Code 保留下來。重新 activate 時得把這些資料夾對應的 token 從 secrets 撈回來，不能只
 * 靠「使用者按過一次 Connect」這個一次性動作。
 */
async function rehydrateTokensForOpenFolders(): Promise<void> {
  if (!extensionContext) return;
  const addresses = new Set(
    (vscode.workspace.workspaceFolders ?? [])
      .filter((f) => f.uri.scheme === SCHEME)
      .map((f) => f.uri.authority)
  );
  await Promise.all(
    Array.from(addresses).map(async (address) => {
      if (tokensByAddress.has(address)) return;
      const token = await extensionContext!.secrets.get(`moonclicker_token_${address}`);
      if (token) tokensByAddress.set(address, token);
    })
  );
}

export function activate(context: vscode.ExtensionContext): void {
  startMdnsDaemon();
  extensionContext = context;
  connection = new WorkbenchConnection();
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBarItem.show();
  renderStatusBar({ status: "disconnected" });

  workspaceProvider = new WorkspaceTreeProvider();
  vscode.window.registerTreeDataProvider("moonclicker.workspace", workspaceProvider);

  fileSystemProvider = new MoonclickerFileSystemProvider((address) => tokensByAddress.get(address));
  context.subscriptions.push(
    vscode.workspace.registerFileSystemProvider(SCHEME, fileSystemProvider, { isCaseSensitive: true })
  );
  rehydrateTokensForOpenFolders();

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      workspaceProvider.refresh();
      rehydrateTokensForOpenFolders();
    })
  );

  const logChannel = vscode.window.createOutputChannel("MoonClicker Script Log");
  const dataChannel = vscode.window.createOutputChannel("MoonClicker Script Data");

  connection.onDidChangeState((state) => {
    renderStatusBar(state);
    workspaceProvider.updateState(state, activeToken);
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
    } else if (event.event.case === "fileChange") {
      const state = connection?.state;
      if (state?.status === "connected") {
        fileSystemProvider.applyRemoteChange(state.address, event.event.value);
      }
    }
    postStreamEventToMirror(event.event);
  });

  context.subscriptions.push(
    statusBarItem,
    logChannel,
    dataChannel,
    vscode.commands.registerCommand("moonclicker.connect", connectCommand),
    vscode.commands.registerCommand("moonclicker.disconnect", () => connection?.disconnect()),
    vscode.commands.registerCommand("moonclicker.openScript", openScriptCommand),
    vscode.commands.registerCommand("moonclicker.run", runCommand),
    vscode.commands.registerCommand("moonclicker.runRemote", runRemoteCommand),
    vscode.commands.registerCommand("moonclicker.openMirror", openMirrorCommand),
    vscode.commands.registerCommand("moonclicker.setupStubs", setupStubsCommand),
    vscode.commands.registerCommand("moonclicker.toggleMirror", toggleMirrorCommand),
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

/** 依 tree item 或（沒帶 item 時）先選裝置、再選腳本，把裝置上的腳本掛成 virtual workspace folder。 */
async function openScriptCommand(item?: RemoteScriptItem): Promise<void> {
  if (item) {
    await openScriptAsWorkspaceFolder(item.address, item.summary);
    return;
  }
  const address = connectedAddress();
  if (!address) return;
  const scripts = await listScripts(address, activeToken).catch((err) => {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
    return undefined;
  });
  if (!scripts || scripts.length === 0) {
    if (scripts) vscode.window.showInformationMessage("MoonClicker: 裝置上還沒有任何腳本");
    return;
  }
  const picked = await vscode.window.showQuickPick(
    scripts.map((s) => ({ label: s.name, description: s.id, script: s })),
    { placeHolder: "選擇要開啟的 Script Folder" },
  );
  if (picked) await openScriptAsWorkspaceFolder(address, picked.script);
}

/** F5／「Run Current Script」：對著目前作用中編輯器所在的 virtual workspace folder 觸發執行。 */
async function runCommand(): Promise<void> {
  const uri = vscode.window.activeTextEditor?.document.uri;
  if (!uri || uri.scheme !== SCHEME) {
    vscode.window.showErrorMessage("MoonClicker: 請先透過側邊欄開啟一個裝置上的腳本");
    return;
  }
  const { address, scriptId } = parseUri(uri);
  try {
    insertRunDivider();
    await runScript(address, scriptId, tokensByAddress.get(address));
    vscode.window.showInformationMessage(`MoonClicker: 已在裝置上觸發「${scriptId}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
  }
}

async function setupStubsCommand(): Promise<void> {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    vscode.window.showErrorMessage("MoonClicker: 請先開啟專案資料夾");
    return;
  }
  if (workspaceFolder.uri.scheme === SCHEME) {
    // LuaLS 是原生行程，直接對解碼後的 OS 路徑做 io.open，讀不到 moonclicker: 這個
    // virtual scheme（查證見 vscode-fsprovider-plan.md）——這裡沒有繞過空間。
    vscode.window.showErrorMessage("MoonClicker: 裝置上的腳本沒有 Lua 型別提示，僅支援本機資料夾");
    return;
  }

  try {
    const rootPath = workspaceFolder.uri.fsPath;
    ensureLuarcConfigured(rootPath);
    vscode.window.showInformationMessage(`MoonClicker: 已在工作區根目錄設定 Lua API 提示 (.luarc.json)`);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: 設定 Lua 提示失敗 - ${(err as Error).message}`);
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
    vscode.window.showInformationMessage(`MoonClicker: 已在裝置上觸發「${id}」執行`);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
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
      vscode.window.showErrorMessage("MoonClicker: 裝置上沒有可用的 display");
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
          vscode.window.showInformationMessage(`MoonClicker: 實體螢幕 (Display ${displayId}) 鏡像管線已啟動`);
        } catch (e) {
          vscode.window.showErrorMessage(`MoonClicker: 啟動鏡像失敗: ${(e as Error).message}`);
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
      vscode.window.showErrorMessage("MoonClicker: 裝置上沒有找到顯示器");
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
      `MoonClicker: 顯示器 ${targetDisplay.id} (${targetDisplay.name}) 鏡像已${nextState ? "開啟" : "關閉"}`
    );
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: 切換鏡像失敗 - ${(err as Error).message}`);
  }
}

function renderStatusBar(state: ConnectionState): void {
  if (!statusBarItem) return;
  switch (state.status) {
    case "disconnected":
      statusBarItem.text = "$(circle-slash) MoonClicker: 未連線";
      statusBarItem.command = "moonclicker.connect";
      break;
    case "connecting":
      statusBarItem.text = `$(sync~spin) MoonClicker: 連線中 ${state.address}`;
      statusBarItem.command = undefined;
      break;
    case "connected":
      statusBarItem.text = `$(check) MoonClicker: 已連線 ${state.address}`;
      statusBarItem.command = undefined;
      break;
    case "error":
      statusBarItem.text = `$(error) MoonClicker: 連線失敗`;
      statusBarItem.command = "moonclicker.connect";
      break;
  }
}
