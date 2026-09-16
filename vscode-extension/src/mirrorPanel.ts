import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { MirrorConnection, type MirrorConnectionState } from "./mirrorConnection";
import { FrameStalenessTracker, type StalenessState } from "./frameStaleness";
import { saveTemplate, type TemplateRoi } from "./templateSync";
import { listScripts, type ScriptSummary } from "./scriptSync";
import { listDisplays, toggleDisplayMirror } from "./displaySync";

/**
 * 收幀間隔超過這個值就當作卡住（見 [FrameStalenessTracker]）。裝置端出幀間隔上限是
 * `MirrorFrameSource.MIN_FRAME_INTERVAL_MS`（100ms），抓 30 倍當緩衝，避免區網正常的
 * 短暫延遲被誤判成卡住。
 */
const STALE_AFTER_MS = 3000;

let panel: vscode.WebviewPanel | undefined;
let connection: MirrorConnection | undefined;
const mirrorOutputChannel = vscode.window.createOutputChannel("ReLC Mirror");

/**
 * `relc.openMirror` 的面板邏輯（見 #77、#78）。跟 `extension.ts` 的 [WorkbenchConnection] 是完全
 * 分開的一份連線與狀態——再次呼叫這個指令只會重啟 mirror 自己的連線，不影響 log/data.set
 * 那條 WebSocket，反之亦然。
 */
export function postStreamEventToMirror(event: any): void {
  safePostMessage({ type: "streamEvent", event });
}

export function openMirrorPanel(extensionUri: vscode.Uri, address: string, displayId: number): void {
  connection?.stop();
  connection = undefined;

  if (panel) {
    try {
      panel.title = mirrorTitle(displayId);
      panel.reveal(vscode.ViewColumn.Beside);
    } catch {
      panel = undefined;
    }
  }

  if (!panel) {
    const newPanel = vscode.window.createWebviewPanel(
      "relc.mirror",
      mirrorTitle(displayId),
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true },
    );
    panel = newPanel;
    const jmuxerPath = vscode.Uri.joinPath(extensionUri, "dist", "jmuxer.min.js");
    const jmuxerUri = newPanel.webview.asWebviewUri(jmuxerPath).toString();
    newPanel.webview.html = renderHtml(jmuxerUri, newPanel.webview.cspSource, extensionUri.fsPath);
    // 面板關閉是「串流要確實停止」的兩個入口之一（另一個是下面的 stop 訊息）——
    // 兩者都導向同一個 connection.stop()，不留背景繼續拉流的路徑。
    newPanel.onDidDispose(() => {
      const conn = connection;
      connection = undefined;
      panel = undefined;
      conn?.stop();
    });
    newPanel.webview.onDidReceiveMessage(async (message: {
      type?: string;
      scriptId?: string;
      templateName?: string;
      roi?: TemplateRoi;
      pngBase64?: string;
      displayId?: number;
      enable?: boolean;
    }) => {
      if (message?.type === "stop") {
        connection?.stop();
      } else if (message?.type === "requestScripts") {
        try {
          const scripts = await listScripts(address);
          safePostMessage({ type: "scripts", scripts });
        } catch {
          safePostMessage({ type: "scripts", scripts: [] });
        }
      } else if (message?.type === "saveTemplate") {
        const { scriptId, templateName, roi, pngBase64 } = message;
        if (!scriptId || !templateName || !roi || !pngBase64) {
          vscode.window.showErrorMessage("ReLC: 裁切模板存檔參數不完整");
          return;
        }
        try {
          const pngBuffer = Buffer.from(pngBase64, "base64");
          await saveTemplate(address, scriptId, templateName, roi, pngBuffer);
          safePostMessage({ type: "saveTemplateResult", success: true });
          vscode.window.showInformationMessage(`ReLC: 模板「${templateName}」已成功存檔到 ${scriptId}`);
        } catch (err) {
          const errMsg = (err as Error).message;
          safePostMessage({
            type: "saveTemplateResult",
            success: false,
            error: errMsg,
          });
          mirrorOutputChannel.appendLine(`[ReLC Save Template Error] ${errMsg}`);
          mirrorOutputChannel.show(true);
          vscode.window.showErrorMessage(`ReLC 儲存模板失敗: ${errMsg}`);
        }
      } else if (message?.type === "switchDisplay") {
        if (typeof message.displayId === "number") {
          openMirrorPanel(extensionUri, address, message.displayId);
        }
      } else if (message?.type === "refreshDisplays") {
        refreshDisplays();
      } else if (message?.type === "error") {
        mirrorOutputChannel.appendLine(`[Webview Error] ${(message as any).message}`);
        mirrorOutputChannel.show(true);
      } else if (message?.type === "log") {
        mirrorOutputChannel.appendLine(`[Webview Log] ${(message as any).message}`);
      } else if (message?.type === "toggleMirror") {
        const targetDisplayId = message.displayId;
        if (typeof targetDisplayId === "number") {
          const enable = message.enable !== false;
          toggleDisplayMirror(address, targetDisplayId, enable)
            .then(() => {
              vscode.window.showInformationMessage(`ReLC: 顯示器 ${targetDisplayId} 鏡像已${enable ? "開啟" : "關閉"}`);
              openMirrorPanel(extensionUri, address, targetDisplayId);
            })
            .catch((err) => {
              vscode.window.showErrorMessage(`ReLC: 切換鏡像失敗: ${(err as Error).message}`);
            });
        }
      }
    });
  }

  const mirror = new MirrorConnection();
  connection = mirror;
  const staleness = new FrameStalenessTracker(STALE_AFTER_MS, postStaleness);

  const refreshDisplays = () => {
    listDisplays(address)
      .then((displays) => safePostMessage({ type: "displays", displays, currentDisplayId: displayId }))
      .catch(() => {});
  };
  refreshDisplays();

  mirror.onDidChangeState((state) => {
    postState(state);
    if (state.status === "connected") {
      staleness.armFromConnect();
      // 連線成功時自動請求腳本清單以供裁切存檔選擇
      listScripts(address)
        .then((scripts) => safePostMessage({ type: "scripts", scripts }))
        .catch((err) => {
          mirrorOutputChannel.appendLine(`[ReLC Mirror] Failed to list scripts: ${(err as Error).message}`);
        });
      refreshDisplays();
    } else if (state.status === "error") {
      mirrorOutputChannel.appendLine(`[ReLC Mirror Error] ${state.message}`);
      mirrorOutputChannel.show(true);
      staleness.disarm();
    } else {
      staleness.disarm();
    }
  });
  mirror.onDidReceiveFrame((frame) => {
    staleness.noteFrame();
    postFrame(frame);
  });
  mirror.start(address, displayId);
}

/** extension 停用時的收尾——面板還開著也要確實停止串流，不留給 VS Code 自己處理。 */
export function disposeMirrorPanel(): void {
  const conn = connection;
  const p = panel;
  connection = undefined;
  panel = undefined;
  conn?.stop();
  try {
    p?.dispose();
  } catch {}
}

function mirrorTitle(displayId: number): string {
  return `ReLC Mirror — display ${displayId}`;
}

function safePostMessage(message: unknown): void {
  try {
    panel?.webview.postMessage(message);
  } catch {
    // webview might be disposed
  }
}

function postState(state: MirrorConnectionState): void {
  safePostMessage({ type: "state", state });
}

function postFrame(frame: Buffer): void {
  safePostMessage({ type: "frame", data: new Uint8Array(frame) });
}

function postStaleness(state: StalenessState): void {
  safePostMessage({ type: "staleness", stale: state === "stale" });
}

function renderHtml(jmuxerUri: string, cspSource: string, extensionPath?: string): string {
  const root = extensionPath || path.resolve(__dirname, "..");
  const mediaDir = path.join(root, "media");
  const css = fs.readFileSync(path.join(mediaDir, "mirror.css"), "utf8");
  const bodyHtml = fs.readFileSync(path.join(mediaDir, "mirror.html"), "utf8");
  const js = fs.readFileSync(path.join(mediaDir, "mirror.js"), "utf8");
  return `<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src blob:; img-src data: blob:; style-src 'unsafe-inline' ${cspSource}; script-src 'unsafe-inline' ${cspSource};" />
<script src="${jmuxerUri}"></script>
<style>
${css}
</style>
</head>
<body>
${bodyHtml}
  <script>
${js}
  </script>
</body>
</html>`;
}
