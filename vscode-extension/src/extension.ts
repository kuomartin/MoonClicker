import * as path from "node:path";
import * as vscode from "vscode";
import { ConnectionState, WorkbenchConnection } from "./workbenchConnection";
import type { ReplEvent } from "./generated/workbench_stream_event_pb";
import { listDisplays } from "./displaySync";
import { listScripts, runScript } from "./scriptSync";
import { FolderSyncController } from "./folderSyncController";
import { disposeMirrorPanel, openMirrorPanel, postStreamEventToMirror } from "./mirrorPanel";
import { WorkspaceTreeProvider, RemoteScriptItem } from "./treeViews";
import { startMdnsDaemon, getCachedDevices, mdnsEvents, checkDeviceHealth, refreshMdns } from "./mdnsDiscovery";
import { pairWithPin } from "./authSync";
import { stopRun } from "./visionTest";
import { ScriptDataStore } from "./scriptStreamState";
import { errorText, localize } from "./l10n";
import { closeRepl, listRepls, openRepl, resetRepl, type ReplStatus } from "./repl";
import { mcpServerConfig } from "./mcp";
import { ReplTerminal } from "./replTerminal";

let connection: WorkbenchConnection | undefined;
let statusBarItem: vscode.StatusBarItem | undefined;
/** 腳本執行中才出現，點擊即停止。 */
let runStatusItem: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let workspaceProvider: WorkspaceTreeProvider;
let activeToken: string | undefined;
let folderSync: FolderSyncController;
let scriptData = new ScriptDataStore();
let runningScriptIds: string[] = [];
/** 開著的 REPL 終端機；關掉終端機不會關掉裝置上的 REPL。 */
let repl: { terminal: vscode.Terminal; pty: ReplTerminal } | undefined;
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
  runStatusItem.tooltip = vscode.l10n.t("Stop scripts on the device");
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
      setRunningScripts([]);
    }
    if (state.status !== "connected") scriptData.clear();
    if (state.status === "error") {
      vscode.window.showErrorMessage(
        vscode.l10n.t("MoonClicker: Failed to connect to {0}: {1}", state.address, localize(state.message)),
      );
    }
  });

  connection.onDidReceiveStreamEvent((event) => {
    if (event.event.case === "log") {
      const { scriptId, line } = event.event.value;
      logChannel.appendLine(`[${scriptId}] ${line}`);
      postStreamEventToMirror({ case: "log", value: { scriptId, line } });
    } else if (event.event.case === "data") {
      const scriptId = event.event.value.scriptId;
      const data = scriptData.apply(event.event.value);
      dataChannel.clear();
      dataChannel.appendLine(JSON.stringify(scriptData.all(), null, 2));
      postStreamEventToMirror({ case: "data", value: { scriptId, data } });
    } else if (event.event.case === "runState") {
      const runningScriptIds = event.event.value.runningScriptIds;
      setRunningScripts(runningScriptIds);
      postStreamEventToMirror({ case: "runState", value: { runningScriptIds } });
    } else if (event.event.case === "repl") {
      handleReplEvent(event.event.value, dataChannel);
    } else if (event.event.case === "fileChange") {
      folderSync.handleRemoteChange(event.event.value);
      // 整支腳本新增、刪除，或名稱可能改了，側邊欄的清單要跟著更新。
      const changedPath = event.event.value.path;
      if (!changedPath || changedPath === "main.lua" || changedPath === "script.json") workspaceProvider.refresh();
    }
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
    vscode.commands.registerCommand("moonclicker.repl.open", () => openReplCommand()),
    vscode.commands.registerCommand("moonclicker.repl.runSelection", runSelectionInReplCommand),
    vscode.commands.registerCommand("moonclicker.repl.interrupt", () => repl?.pty.interrupt()),
    vscode.commands.registerCommand("moonclicker.repl.reset", resetReplCommand),
    vscode.commands.registerCommand("moonclicker.repl.close", closeReplCommand),
    vscode.commands.registerCommand("moonclicker.copyMcpConfig", async () => {
      const address = connectedAddress();
      if (!address) return;
      await vscode.env.clipboard.writeText(mcpServerConfig(address, activeToken));
      vscode.window.showInformationMessage(
        vscode.l10n.t("MoonClicker: MCP server config copied. It contains the Workbench token; keep it private."),
      );
    }),
    vscode.commands.registerCommand("moonclicker.refresh", () => {
      workspaceProvider.refresh();
      folderSync.resync();
    }),
  );
}

function handleReplEvent(event: ReplEvent, dataChannel: vscode.OutputChannel): void {
  switch (event.event.case) {
    case "list":
      repl?.pty.onList(event.event.value.repls);
      break;
    case "log":
      if (event.replId === repl?.pty.replId) repl.pty.printLog(event.event.value);
      break;
    case "chunk":
      if (event.replId === repl?.pty.replId && event.event.value.client !== repl.pty.client) {
        repl.pty.printForeign(event.event.value);
      }
      break;
    case "data":
      scriptData.apply({ ...event.event.value, scriptId: replDataKey(event.replId) });
      dataChannel.clear();
      dataChannel.appendLine(JSON.stringify(scriptData.all(), null, 2));
      break;
  }
}

/** REPL 的 `data` 在資料面板上的名字。腳本 id 是資料夾名稱，不會有空白與括號。 */
function replDataKey(replId: string): string {
  return `(REPL ${replId})`;
}

/**
 * 這個視窗只有一個 REPL 終端機，已經有就叫出來。沒有時：裝置上有開著的 REPL（例如 MCP 開的）
 * 就讓使用者選接上哪一個或開新的，沒有就直接選顯示器開新的。
 */
async function openReplCommand(): Promise<ReplTerminal | undefined> {
  if (repl) {
    repl.terminal.show();
    return repl.pty;
  }
  const address = connectedAddress();
  if (!address) return undefined;
  try {
    const status = await chooseRepl(address);
    if (!status) return undefined;
    const endpoint = () => {
      const state = connection?.state;
      return state?.status === "connected" ? { address: state.address, token: activeToken } : undefined;
    };
    const pty = new ReplTerminal(
      endpoint,
      status,
      () => {
        repl = undefined;
      },
      () => void closeReplCommand(),
    );
    const terminal = vscode.window.createTerminal({ name: vscode.l10n.t("MoonClicker REPL {0}", status.id), pty });
    repl = { terminal, pty };
    terminal.show();
    return pty;
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
    return undefined;
  }
}

async function chooseRepl(address: string): Promise<ReplStatus | undefined> {
  const existing = await listRepls(address, activeToken);
  if (existing.length > 0) {
    const items: (vscode.QuickPickItem & { repl?: ReplStatus })[] = [
      { label: vscode.l10n.t("New REPL") },
      ...existing.map((r) => ({
        label: vscode.l10n.t("Attach to REPL {0}", r.id),
        description: vscode.l10n.t("display {0}", r.displayId),
        repl: r,
      })),
    ];
    const picked = await vscode.window.showQuickPick(items, {
      placeHolder: vscode.l10n.t("Open a new REPL or attach to one already on the device"),
    });
    if (!picked) return undefined;
    if (picked.repl) return picked.repl;
  }
  const displayId = await pickDisplay(address);
  if (displayId === undefined) return undefined;
  const editor = vscode.window.activeTextEditor;
  const scriptId = editor ? folderSync.scriptIdFor(editor.document.uri.fsPath) : undefined;
  return openRepl(address, displayId, scriptId, activeToken);
}

async function pickDisplay(address: string): Promise<number | undefined> {
  const displays = await listDisplays(address, activeToken);
  const picked = await vscode.window.showQuickPick(
    displays.map((d) => ({ label: d.name, description: vscode.l10n.t("display {0}", d.id), id: d.id })),
    { placeHolder: vscode.l10n.t("Choose the display the REPL runs on") },
  );
  return picked?.id;
}

/** Shift+Enter：送出選取範圍，沒有選取時送出游標所在的那一行。 */
async function runSelectionInReplCommand(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;
  const code = editor.selection.isEmpty
    ? editor.document.lineAt(editor.selection.active.line).text
    : editor.document.getText(editor.selection);
  if (code.trim() === "") return;
  const pty = await openReplCommand();
  pty?.runCode(code);
}

async function resetReplCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    if (!repl) return;
    await resetRepl(address, repl.pty.replId, activeToken);
    repl?.pty.print(vscode.l10n.t("The REPL was reset; globals are cleared."));
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
  }
}

async function closeReplCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  try {
    if (!repl) return;
    await closeRepl(address, repl.pty.replId, activeToken);
    repl?.terminal.dispose();
    repl = undefined;
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
  }
}

export function deactivate(): void {
  connection?.disconnect();
  disposeMirrorPanel();
}

function insertRunDivider(): void {
  const divider = `--- Run triggered at ${new Date().toLocaleTimeString()} ---`;
  postStreamEventToMirror({ case: "log", value: { scriptId: "", line: divider } });
}

interface ConnectItem extends vscode.QuickPickItem {
  address?: string;
  isManual?: boolean;
}

async function connectCommand(): Promise<void> {
  if (!extensionContext) return;

  const quickPick = vscode.window.createQuickPick<ConnectItem>();
  quickPick.title = vscode.l10n.t("MoonClicker: Searching for devices on the local network...");
  quickPick.placeholder = vscode.l10n.t("Choose a MoonClicker device to connect to");
  quickPick.busy = true;

  const baseItems: ConnectItem[] = [
    {
      label: "$(edit) " + vscode.l10n.t("Enter IP:port manually"),
      description: vscode.l10n.t("Type the device address yourself"),
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
        detail: vscode.l10n.t("Checking status..."),
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
            detail: isOnline ? vscode.l10n.t("Discovered via mDNS") : vscode.l10n.t("(Offline / Cached)"),
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
        prompt: vscode.l10n.t("Device IP:port (see Settings > Script Workbench on the device)"),
        placeHolder: "192.168.1.23:8787",
        validateInput: (value) =>
          /^[^\s:]+:\d+$/.test(value) ? undefined : vscode.l10n.t("Format must be ip:port"),
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
        prompt: vscode.l10n.t(
          "Connecting to {0} requires pairing. Enter the 6-digit PIN shown on the Android device (turn on pairing mode on the device first).",
          address,
        ),
        placeHolder: "123456",
        password: true,
        validateInput: (val) => (/^\d{6}$/.test(val) ? undefined : vscode.l10n.t("The PIN must be 6 digits")),
      });

      if (!pin) return;

      try {
        token = await pairWithPin(address, pin);
        await extensionContext!.secrets.store(secretKey, token);
        vscode.window.showInformationMessage(vscode.l10n.t("MoonClicker: Paired with {0}. Credentials saved.", address));
      } catch (err) {
        vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Pairing failed: {0}", errorText(err)));
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
    vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Not connected to a device"));
    return undefined;
  }
  return state.address;
}

async function pickScript(address: string): Promise<string | undefined> {
  const scripts = await listScripts(address, activeToken);
  if (scripts.length === 0) {
    vscode.window.showInformationMessage(vscode.l10n.t("MoonClicker: There are no scripts on the device yet"));
    return undefined;
  }
  const picked = await vscode.window.showQuickPick(
    scripts.map((s) => ({ label: s.name, description: s.id, id: s.id })),
    { placeHolder: vscode.l10n.t("Choose a script") },
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
      folderSync.root
        ? vscode.l10n.t("MoonClicker: The local folder has no main.lua for \"{0}\"", scriptId)
        : vscode.l10n.t("MoonClicker: Connect and choose a scripts folder first"),
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
    vscode.window.showErrorMessage(vscode.l10n.t("MoonClicker: Open the script to run from the synced scripts folder"));
    return;
  }
  try {
    if (editor.document.isDirty) await editor.document.save();
    await folderSync.flush(editor.document.uri.fsPath);
    insertRunDivider();
    await runScript(address, scriptId, activeToken);
    vscode.window.showInformationMessage(vscode.l10n.t("MoonClicker: Started \"{0}\" on the device", scriptId));
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
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
    vscode.window.showInformationMessage(vscode.l10n.t("MoonClicker: Started \"{0}\" on the device", id));
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
  }
}

function setRunningScripts(scriptIds: string[]): void {
  runningScriptIds = scriptIds;
  workspaceProvider.setRunningScripts(scriptIds);
  if (!runStatusItem) return;
  if (scriptIds.length > 0) {
    runStatusItem.text = "$(debug-stop) " + vscode.l10n.t("Running: {0}", scriptIds.join(", "));
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
    vscode.window.showWarningMessage(
      vscode.l10n.t("MoonClicker: Could not list displays, opening display 0: {0}", errorText(err)),
    );
  }
  openMirrorPanel(extensionContext.extensionUri, address, displayId, activeToken, (id) => {
    extensionContext?.globalState.update(LAST_DISPLAY_KEY, id);
  });
}

/** 停止裝置上正在執行的腳本；同時有多支在跑時先問要停哪一支。 */
async function stopCommand(): Promise<void> {
  const address = connectedAddress();
  if (!address) return;
  let scriptId: string | undefined;
  if (runningScriptIds.length === 1) {
    scriptId = runningScriptIds[0];
  } else if (runningScriptIds.length > 1) {
    const all = "$(debug-stop) " + vscode.l10n.t("Stop all");
    const picked = await vscode.window.showQuickPick([...runningScriptIds, all], {
      placeHolder: vscode.l10n.t("Which script do you want to stop?"),
    });
    if (!picked) return;
    scriptId = picked === all ? undefined : picked;
  }
  try {
    await stopRun(address, activeToken, scriptId);
  } catch (err) {
    vscode.window.showErrorMessage(`MoonClicker: ${errorText(err)}`);
  }
}

function renderStatusBar(state: ConnectionState): void {
  if (!statusBarItem) return;
  switch (state.status) {
    case "disconnected":
      statusBarItem.text = "$(circle-slash) " + vscode.l10n.t("MoonClicker: Disconnected");
      statusBarItem.tooltip = vscode.l10n.t("Connect to a device");
      statusBarItem.command = "moonclicker.connect";
      break;
    case "connecting":
      statusBarItem.text = "$(sync~spin) " + vscode.l10n.t("MoonClicker: Connecting to {0}", state.address);
      statusBarItem.tooltip = undefined;
      statusBarItem.command = undefined;
      break;
    case "connected":
      statusBarItem.text = "$(check) " + vscode.l10n.t("MoonClicker: Connected to {0}", state.address);
      statusBarItem.tooltip = vscode.l10n.t("Open the Workbench panel");
      statusBarItem.command = "moonclicker.openWorkbench";
      break;
    case "error":
      statusBarItem.text = "$(error) " + vscode.l10n.t("MoonClicker: Connection failed");
      statusBarItem.tooltip = vscode.l10n.t("Reconnect");
      statusBarItem.command = "moonclicker.connect";
      break;
  }
}
