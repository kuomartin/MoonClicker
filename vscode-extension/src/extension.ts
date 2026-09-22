import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import { mergeLuarc } from "./luarc";
import { listDisplays, toggleDisplayMirror, type DisplaySummary } from "./displaySync";
import { listScripts, runScript, ScriptHttpError, type ScriptSummary } from "./scriptSync";
import {
  createDirectoryOnDevice,
  deleteFileOnDevice,
  mirrorDir,
  pullScriptToMirror,
  pushFileToDevice,
  removeMirroredFile,
  renameFileOnDevice,
  syncChangedFileToMirror,
} from "./scriptMirror";
import { FileChangeEvent, FileChangeEvent_Kind } from "./generated/workbench_stream_event_pb";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, RemoteScriptItem } from "./treeViews";
import { startMdnsDaemon, getCachedDevices, mdnsEvents, checkDeviceHealth, refreshMdns } from "./mdnsDiscovery";
import { pairWithPin } from "./authSync";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;
let activeToken: string | undefined;
/** 同時可能有好幾個已開啟的鏡像資料夾——可能來自不同裝置——所以每個 address 各自記自己
 *  的 token，不能只靠 [activeToken] 這個「目前連線」。 */
const tokensByAddress = new Map<string, string>();

interface OpenMirror {
  address: string;
  scriptId: string;
  watcher: vscode.FileSystemWatcher;
}
/** key 是鏡像資料夾的 fsPath（`mirrorDir()` 算出來的那個隱藏路徑）。 */
const openMirrors = new Map<string, OpenMirror>();

function findMirrorForLocalPath(fsPath: string): (OpenMirror & { destDir: string }) | undefined {
  for (const [destDir, mirror] of openMirrors) {
    if (fsPath === destDir || fsPath.startsWith(destDir + path.sep)) {
      return { ...mirror, destDir };
    }
  }
  return undefined;
}

interface MirrorRecord {
  address: string;
  scriptId: string;
  destDir: string;
}

/** 持久記錄「這個本機資料夾對應裝置上哪一顆腳本」——跨 activate 存活，見 [rehydrateOpenMirrors]。 */
function loadMirrorRegistry(): MirrorRecord[] {
  return extensionContext?.globalState.get<MirrorRecord[]>("moonclicker.mirrors", []) ?? [];
}

function rememberMirror(record: MirrorRecord): void {
  const records = loadMirrorRegistry().filter((r) => r.destDir !== record.destDir);
  records.push(record);
  extensionContext?.globalState.update("moonclicker.mirrors", records);
}

/**
 * `vscode.workspace.updateWorkspaceFolders` 官方文件明講：第一次加入 workspace folder、或
 * 從空/單一資料夾轉成多資料夾時，擴充套件會被終止重啟——`tokensByAddress`／`activeToken`／
 * `connection`／`openMirrors` 這些純記憶體狀態全部歸零，但已經掛上的鏡像資料夾會被 VS Code
 * 保留下來。重新 activate 時得把這些資料夾對應的 token 從 secrets 撈回來、watcher 重新掛上，
 * 不能只靠「使用者按過一次 Connect」這個一次性動作。
 */
async function rehydrateOpenMirrors(): Promise<void> {
  if (!extensionContext) return;
  const openFolderPaths = new Set((vscode.workspace.workspaceFolders ?? []).map((f) => f.uri.fsPath));
  for (const record of loadMirrorRegistry()) {
    if (!openFolderPaths.has(record.destDir) || openMirrors.has(record.destDir)) continue;
    if (!tokensByAddress.has(record.address)) {
      const token = await extensionContext.secrets.get(`moonclicker_token_${record.address}`);
      if (token) tokensByAddress.set(record.address, token);
    }
    registerMirror(record.address, record.scriptId, record.destDir);
  }
}

/** 記錄「使用者上次手動連線的裝置」，讓 [reconnectLastDevice] 在 [rehydrateOpenMirrors] 同一輪
 *  extension 重啟後，能把使用者剛按過的連線接回去，而不只是把鏡像資料夾的 watcher 接回去。 */
function rememberLastAddress(address: string): void {
  extensionContext?.globalState.update("moonclicker.lastAddress", address);
}

function forgetLastAddress(): void {
  extensionContext?.globalState.update("moonclicker.lastAddress", undefined);
}

/** `openScript()` 掛新 workspace folder 時會觸發文件註明的 extension 重啟，把 [connection] 砍成
 *  全新的 disconnected 實例——使用者剛連上的裝置因此無聲斷線，得靠自己按一次 Connect 才會發現。
 *  這裡用 [rememberLastAddress] 記下的位址，在重啟後自動接回去。 */
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

/**
 * `handleRemoteFileChange` 寫新檔案到本機時，這個資料夾自己的 `FileSystemWatcher` 會看到
 * `onDidCreate`——沒有這個集合的話，會把「裝置推來的變動」當成「使用者新增的檔案」原封
 * 不動推回裝置，形成沒意義的來回。寫入前登記、觸發完清掉；delay 一小段是留給 fs event
 * 真的送達的時間，不是在猜對方到底有沒有收到。
 */
const suppressedCreatePaths = new Set<string>();

/** 對著鏡像資料夾掛 watcher：新增檔案/資料夾、刪除都同步推上裝置。存檔另外走 `onDidSaveTextDocument`。 */
function registerMirror(address: string, scriptId: string, destDir: string): void {
  if (openMirrors.has(destDir)) return;
  rememberMirror({ address, scriptId, destDir });
  const watcher = vscode.workspace.createFileSystemWatcher(
    new vscode.RelativePattern(vscode.Uri.file(destDir), "**/*")
  );
  watcher.onDidCreate(async (uri) => {
    if (suppressedCreatePaths.has(uri.fsPath)) return;
    try {
      const stat = fs.statSync(uri.fsPath);
      const token = tokensByAddress.get(address);
      if (stat.isDirectory()) {
        await createDirectoryOnDevice(address, scriptId, destDir, uri.fsPath, token);
      } else {
        await pushFileToDevice(address, scriptId, destDir, uri.fsPath, token);
      }
    } catch (err) {
      vscode.window.showErrorMessage(`MoonClicker: 同步新增失敗 - ${(err as Error).message}`);
    }
  });
  watcher.onDidDelete(async (uri) => {
    try {
      await deleteFileOnDevice(address, scriptId, destDir, uri.fsPath, tokensByAddress.get(address));
    } catch (err) {
      // 改名會先觸發 onDidRenameFiles（已經呼叫裝置端的 rename，原路徑在裝置上已經不存在），
      // 系統層級的 fs watcher 隨後才看到「舊路徑消失」再補一次 delete——404 是這個競態的
      // 正常結果，不是真的失敗，不用跳錯誤訊息。
      if (err instanceof ScriptHttpError && err.status === 404) return;
      vscode.window.showErrorMessage(`MoonClicker: 同步刪除失敗 - ${(err as Error).message}`);
    }
  });
  extensionContext?.subscriptions.push(watcher);
  openMirrors.set(destDir, { address, scriptId, watcher });
}

async function handleRemoteFileChange(address: string, change: FileChangeEvent, destDir: string): Promise<void> {
  if (change.kind === FileChangeEvent_Kind.DELETED) {
    removeMirroredFile(destDir, change.path);
    return;
  }
  const localPath = path.join(destDir, ...change.path.split("/"));
  suppressedCreatePaths.add(localPath);
  try {
    // 寫回本機磁碟後，VS Code 原生的 file:// 檔案監控會自己偵測到、reload 開著的 buffer——
    // 不用像 virtual FS 那樣手動 fire onDidChangeFile。
    await syncChangedFileToMirror(address, change.scriptId, destDir, change.path, tokensByAddress.get(address));
  } finally {
    setTimeout(() => suppressedCreatePaths.delete(localPath), 2000);
  }
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

  rehydrateOpenMirrors().then(() => reconnectLastDevice());

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      workspaceProvider.refresh();
      rehydrateOpenMirrors().then(() => reconnectLastDevice());
    }),
    vscode.workspace.onDidSaveTextDocument(async (doc) => {
      const mirror = findMirrorForLocalPath(doc.uri.fsPath);
      if (!mirror) return;
      try {
        await pushFileToDevice(mirror.address, mirror.scriptId, mirror.destDir, doc.uri.fsPath, tokensByAddress.get(mirror.address));
      } catch (err) {
        vscode.window.showErrorMessage(`MoonClicker: 同步存檔失敗 - ${(err as Error).message}`);
      }
    }),
    vscode.workspace.onDidRenameFiles(async (event) => {
      for (const { oldUri, newUri } of event.files) {
        const mirror = findMirrorForLocalPath(oldUri.fsPath);
        if (!mirror) continue;
        try {
          await renameFileOnDevice(mirror.address, mirror.scriptId, mirror.destDir, oldUri.fsPath, newUri.fsPath, tokensByAddress.get(mirror.address));
        } catch (err) {
          vscode.window.showErrorMessage(`MoonClicker: 同步改名失敗 - ${(err as Error).message}`);
        }
      }
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
        const address = state.address;
        const change = event.event.value;
        const destDir = extensionContext ? mirrorDir(extensionContext.globalStorageUri.fsPath, address, change.scriptId) : undefined;
        if (destDir && openMirrors.has(destDir)) {
          handleRemoteFileChange(address, change, destDir).catch((err) => {
            vscode.window.showErrorMessage(`MoonClicker: 同步裝置變更失敗 - ${(err as Error).message}`);
          });
        }
      }
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
    rememberLastAddress(address);
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

/** 整份 pull 到本機隱藏鏡像資料夾、掛 watcher、配好 Lua 型別提示，再掛成 workspace folder。 */
async function openScript(address: string, summary: ScriptSummary): Promise<void> {
  if (!extensionContext) return;
  const destDir = mirrorDir(extensionContext.globalStorageUri.fsPath, address, summary.id);
  if ((vscode.workspace.workspaceFolders ?? []).some((f) => f.uri.fsPath === destDir)) return;

  try {
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: `MoonClicker: 下載「${summary.name || summary.id}」...` },
      () => pullScriptToMirror(address, summary.id, destDir, tokensByAddress.get(address)),
    );
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${(err as Error).message}`);
    return;
  }

  registerMirror(address, summary.id, destDir);
  // 盡力而為：Lua 型別提示失敗不該擋掉「腳本已經下載好、可以開始編輯」這件事。
  try {
    ensureLuarcConfigured(destDir);
  } catch {}

  vscode.workspace.updateWorkspaceFolders(vscode.workspace.workspaceFolders?.length ?? 0, 0, {
    uri: vscode.Uri.file(destDir),
    name: summary.name || summary.id,
  });
}

/** 依 tree item 或（沒帶 item 時）先選裝置、再選腳本。 */
async function openScriptCommand(item?: RemoteScriptItem): Promise<void> {
  if (item) {
    await openScript(item.address, item.summary);
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
  if (picked) await openScript(address, picked.script);
}

/** F5／「Run Current Script」：對著目前作用中編輯器所在的鏡像資料夾觸發執行。 */
async function runCommand(): Promise<void> {
  const fsPath = vscode.window.activeTextEditor?.document.uri.fsPath;
  const mirror = fsPath ? findMirrorForLocalPath(fsPath) : undefined;
  if (!mirror) {
    vscode.window.showErrorMessage("MoonClicker: 請先透過側邊欄開啟一個裝置上的腳本");
    return;
  }
  try {
    insertRunDivider();
    await runScript(mirror.address, mirror.scriptId, tokensByAddress.get(mirror.address));
    vscode.window.showInformationMessage(`MoonClicker: 已在裝置上觸發「${mirror.scriptId}」執行`);
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
