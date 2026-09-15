import * as vscode from "vscode";
import { MirrorConnection, type MirrorConnectionState } from "./mirrorConnection";
import { FrameStalenessTracker, type StalenessState } from "./frameStaleness";

/**
 * 收幀間隔超過這個值就當作卡住（見 [FrameStalenessTracker]）。裝置端出幀間隔上限是
 * `MirrorFrameSource.MIN_FRAME_INTERVAL_MS`（100ms），抓 30 倍當緩衝，避免區網正常的
 * 短暫延遲被誤判成卡住。
 */
const STALE_AFTER_MS = 3000;

let panel: vscode.WebviewPanel | undefined;
let connection: MirrorConnection | undefined;

/**
 * `relc.openMirror` 的面板邏輯（見 #77）。跟 `extension.ts` 的 [WorkbenchConnection] 是完全
 * 分開的一份連線與狀態——再次呼叫這個指令只會重啟 mirror 自己的連線，不影響 log/data.set
 * 那條 WebSocket，反之亦然。
 */
export function openMirrorPanel(address: string, displayId: number): void {
  connection?.stop();

  if (panel) {
    panel.title = mirrorTitle(displayId);
    panel.reveal(vscode.ViewColumn.Beside);
  } else {
    panel = vscode.window.createWebviewPanel(
      "relc.mirror",
      mirrorTitle(displayId),
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true },
    );
    panel.webview.html = renderHtml();
    // 面板關閉是「串流要確實停止」的兩個入口之一（另一個是下面的 stop 訊息）——
    // 兩者都導向同一個 connection.stop()，不留背景繼續拉流的路徑。
    panel.onDidDispose(() => {
      connection?.stop();
      connection = undefined;
      panel = undefined;
    });
    panel.webview.onDidReceiveMessage((message: { type?: string }) => {
      if (message?.type === "stop") connection?.stop();
    });
  }

  const mirror = new MirrorConnection();
  connection = mirror;
  const staleness = new FrameStalenessTracker(STALE_AFTER_MS, postStaleness);

  mirror.onDidChangeState((state) => {
    postState(state);
    if (state.status === "connected") {
      staleness.armFromConnect();
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
  connection?.stop();
  panel?.dispose();
}

function mirrorTitle(displayId: number): string {
  return `ReLC Mirror — display ${displayId}`;
}

function postState(state: MirrorConnectionState): void {
  panel?.webview.postMessage({ type: "state", state });
}

function postFrame(frame: Buffer): void {
  panel?.webview.postMessage({ type: "frame", dataUri: `data:image/jpeg;base64,${frame.toString("base64")}` });
}

function postStaleness(state: StalenessState): void {
  panel?.webview.postMessage({ type: "staleness", stale: state === "stale" });
}

function renderHtml(): string {
  return `<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline';" />
<style>
  body { margin: 0; background: var(--vscode-editor-background); color: var(--vscode-editor-foreground); font-family: var(--vscode-font-family); }
  #toolbar { display: flex; align-items: center; gap: 8px; padding: 6px 10px; font-size: 12px; border-bottom: 1px solid var(--vscode-panel-border); }
  #status { flex: 1; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 4px 10px; cursor: pointer; }
  button:hover { background: var(--vscode-button-hoverBackground); }
  #stage { position: relative; width: 100%; }
  #frame { display: block; width: 100%; height: auto; }
  #overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(0, 0, 0, 0.6); color: #fff; font-size: 14px; text-align: center; padding: 16px; box-sizing: border-box; }
  #overlay[hidden] { display: none; }
</style>
</head>
<body>
  <div id="toolbar">
    <span id="status">連線中…</span>
    <button id="stopButton" type="button">停止</button>
  </div>
  <div id="stage">
    <img id="frame" alt="裝置畫面" />
    <div id="overlay">尚未收到畫面</div>
  </div>
  <script>
    const vscode = acquireVsCodeApi();
    const statusEl = document.getElementById("status");
    const frameEl = document.getElementById("frame");
    const overlayEl = document.getElementById("overlay");
    let stale = false;

    document.getElementById("stopButton").addEventListener("click", () => {
      vscode.postMessage({ type: "stop" });
    });

    function showOverlay(text) {
      overlayEl.textContent = text;
      overlayEl.hidden = false;
    }
    function hideOverlayIfConnected() {
      // 卡住的畫面優先於「已連線」文字——不能因為收過幀就掩蓋掉現在其實卡住了。
      if (!stale) overlayEl.hidden = true;
    }

    window.addEventListener("message", (event) => {
      const msg = event.data;
      if (msg.type === "state") {
        renderState(msg.state);
      } else if (msg.type === "frame") {
        frameEl.src = msg.dataUri;
        hideOverlayIfConnected();
      } else if (msg.type === "staleness") {
        stale = msg.stale;
        if (stale) {
          showOverlay("畫面已停滯——裝置可能休眠、WiFi 斷線，或 workbench service 已停止");
        } else {
          hideOverlayIfConnected();
        }
      }
    });

    function renderState(state) {
      switch (state.status) {
        case "disconnected":
          statusEl.textContent = "已停止";
          showOverlay("串流已停止");
          break;
        case "connecting":
          statusEl.textContent = "連線中 display " + state.displayId + "…";
          showOverlay("連線中…");
          break;
        case "connected":
          statusEl.textContent = "已連線 display " + state.displayId;
          break;
        case "error":
          statusEl.textContent = "連線失敗：" + state.message;
          showOverlay("連線失敗：" + state.message);
          break;
      }
    }
  </script>
</body>
</html>`;
}
