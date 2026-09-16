import * as vscode from "vscode";
import { MirrorConnection, type MirrorConnectionState } from "./mirrorConnection";
import { FrameStalenessTracker, type StalenessState } from "./frameStaleness";
import { saveTemplate, type TemplateRoi } from "./templateSync";
import { listScripts, type ScriptSummary } from "./scriptSync";

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
    newPanel.webview.html = renderHtml(jmuxerUri, newPanel.webview.cspSource);
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
          safePostMessage({
            type: "saveTemplateResult",
            success: false,
            error: "缺少必要的裁切參數",
          });
          return;
        }
        try {
          const pngBytes = Buffer.from(pngBase64, "base64");
          await saveTemplate(address, scriptId, templateName, roi, pngBytes);
          safePostMessage({
            type: "saveTemplateResult",
            success: true,
            templateName,
          });
          vscode.window.showInformationMessage(`ReLC: 模板「${templateName}」已成功存入腳本「${scriptId}」`);
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
      } else if (message?.type === "error") {
        mirrorOutputChannel.appendLine(`[Webview Error] ${(message as any).message}`);
        mirrorOutputChannel.show(true);
      } else if (message?.type === "log") {
        mirrorOutputChannel.appendLine(`[Webview Log] ${(message as any).message}`);
      }
    });
  }

  const mirror = new MirrorConnection();
  connection = mirror;
  const staleness = new FrameStalenessTracker(STALE_AFTER_MS, postStaleness);

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
      fetch(`http://${address}/displays`)
        .then(res => res.json())
        .then(displays => safePostMessage({ type: "displays", displays, currentDisplayId: displayId }))
        .catch((err) => {
          mirrorOutputChannel.appendLine(`[ReLC Mirror] Failed to fetch displays: ${(err as Error).message}`);
        });
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

function renderHtml(jmuxerUri: string, cspSource: string): string {
  return `<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src blob:; img-src data: blob:; style-src 'unsafe-inline' ${cspSource}; script-src 'unsafe-inline' ${cspSource};" />
<style>
  body {
    margin: 0;
    background: var(--vscode-editor-background);
    color: var(--vscode-editor-foreground);
    font-family: var(--vscode-font-family);
    display: flex;
    flex-direction: row;
    height: 100vh;
    overflow: hidden;
    user-select: none;
  }
  #leftPanel {
    flex: 1;
    display: flex;
    flex-direction: column;
    border-right: 1px solid var(--vscode-panel-border);
    min-width: 0;
  }
  #rightPanel {
    width: 350px;
    display: flex;
    flex-direction: column;
    background: var(--vscode-sideBar-background);
  }
  #logContainer {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
    font-family: var(--vscode-editor-font-family);
    font-size: 12px;
    border-bottom: 1px solid var(--vscode-panel-border);
  }
  #logToolbar {
    display: flex;
    justify-content: space-between;
    padding: 4px 8px;
    border-bottom: 1px solid var(--vscode-panel-border);
    font-size: 11px;
    background: var(--vscode-editor-background);
  }
  #dataContainer {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
    font-family: var(--vscode-editor-font-family);
    font-size: 12px;
  }
  .logLine { margin: 2px 0; word-wrap: break-word; }
  .runDivider { border-top: 1px dashed var(--vscode-panel-border); margin: 8px 0; }
  .flash { animation: flash 1s; }
  @keyframes flash { from { background: var(--vscode-editor-findMatchHighlightBackground); } to { background: transparent; } }
  .tree-node { margin-left: 12px; margin-bottom: 2px; }
  .tree-key { color: var(--vscode-symbolIcon-propertyForeground); }
  .tree-val-string { color: var(--vscode-debugTokenExpression-string); }
  .tree-val-number { color: var(--vscode-debugTokenExpression-number); }
  .tree-val-boolean { color: var(--vscode-debugTokenExpression-boolean); }
  #toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    font-size: 12px;
    border-bottom: 1px solid var(--vscode-panel-border);
    flex-shrink: 0;
  }
  #status { flex: 1; }
  button {
    background: var(--vscode-button-background);
    color: var(--vscode-button-foreground);
    border: none;
    padding: 4px 10px;
    cursor: pointer;
    border-radius: 2px;
  }
  button:hover:not(:disabled) { background: var(--vscode-button-hoverBackground); }
  button:disabled { opacity: 0.5; cursor: not-allowed; }
  button.secondary {
    background: var(--vscode-button-secondaryBackground);
    color: var(--vscode-button-secondaryForeground);
  }
  button.secondary:hover:not(:disabled) {
    background: var(--vscode-button-secondaryHoverBackground);
  }
  #cropToolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    background: var(--vscode-sideBar-background);
    border-bottom: 1px solid var(--vscode-panel-border);
    font-size: 12px;
    flex-shrink: 0;
  }
  #cropToolbar[hidden] { display: none; }
  input, select {
    background: var(--vscode-input-background);
    color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-input-border);
    padding: 3px 6px;
    font-size: 12px;
    border-radius: 2px;
  }
  input:focus, select:focus {
    outline: 1px solid var(--vscode-focusBorder);
  }
  #cropStatus {
    font-size: 11px;
    color: var(--vscode-errorForeground);
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  #stage {
    position: relative;
    flex: 1;
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    background: #111;
  }
  #frame {
    display: block;
    max-width: 100%;
    max-height: 100%;
    width: auto;
    height: auto;
    object-fit: contain;
    pointer-events: none;
  }
  #cropCanvas {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    cursor: crosshair;
  }
  #cropCanvas[hidden] { display: none; }
  #overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(0, 0, 0, 0.6);
    color: #fff;
    font-size: 14px;
    text-align: center;
    padding: 16px;
    box-sizing: border-box;
    pointer-events: none;
  }
  #overlay[hidden] { display: none; }
</style>
</head>
<body>
<div id="leftPanel">
  <div id="toolbar">
    <select id="displaySelect"></select>
    <span id="status">連線中…</span>
    <button id="cropBtn" type="button" disabled>開始裁切</button>
    <button id="stopButton" type="button">停止</button>
  </div>
  <div id="cropToolbar" hidden>
    <span>腳本:</span>
    <select id="scriptSelect"></select>
    <span>模板名稱:</span>
    <input id="templateNameInput" type="text" placeholder="例如: btn_confirm" />
    <span id="roiInfo" style="color:var(--vscode-descriptionForeground);"></span>
    <span id="cropStatus"></span>
    <button id="saveCropBtn" type="button">儲存</button>
    <button id="cancelCropBtn" type="button" class="secondary">取消</button>
  </div>
  <div id="stage">
    <video id="frame" autoplay muted playsinline></video>
    <canvas id="cropCanvas" hidden></canvas>
    <div id="overlay">尚未收到畫面</div>
  </div>
</div>
<div id="rightPanel">
  <div id="logToolbar">
    <span>Console</span>
    <div>
      <label><input type="checkbox" id="autoScroll" checked> Auto-scroll</label>
      <button id="clearLogBtn" style="padding: 2px 6px; font-size: 10px;">Clear</button>
    </div>
  </div>
  <div id="logContainer"></div>
  <div style="padding: 4px 8px; border-bottom: 1px solid var(--vscode-panel-border); font-size: 11px; background: var(--vscode-editor-background);">Data View</div>
  <div id="dataContainer"></div>
</div>
  <script>
    const vscode = acquireVsCodeApi();
    
    // Catch errors before loading external scripts
    window.onerror = function(message, source, lineno, colno, error) {
      vscode.postMessage({ type: "error", message: "Global error: " + message + " at " + source + ":" + lineno });
    };
  </script>
  <script src="${jmuxerUri}" onerror="vscode.postMessage({ type: 'error', message: 'Failed to load script: ' + this.src })"></script>
  <script>
    function appendLog(msg) {
      const div = document.createElement("div");
      div.textContent = msg;
      div.style.color = "yellow";
      dataContainer.appendChild(div);
      dataContainer.scrollTop = dataContainer.scrollHeight;
    }

    const originalLog = console.log;
    console.log = function(...args) {
      originalLog.apply(console, args);
      appendLog("LOG: " + args.join(" "));
      vscode.postMessage({ type: "log", message: "LOG: " + args.join(" ") });
    };
    const originalError = console.error;
    console.error = function(...args) {
      originalError.apply(console, args);
      appendLog("ERROR: " + args.join(" "));
      vscode.postMessage({ type: "error", message: args.join(" ") });
    };
    const statusEl = document.getElementById("status");
    const frameEl = document.getElementById("frame");
    const overlayEl = document.getElementById("overlay");
    const cropBtn = document.getElementById("cropBtn");
    const stopButton = document.getElementById("stopButton");
    const cropToolbar = document.getElementById("cropToolbar");
    const scriptSelect = document.getElementById("scriptSelect");
    const templateNameInput = document.getElementById("templateNameInput");
    const roiInfo = document.getElementById("roiInfo");
    const cropStatus = document.getElementById("cropStatus");
    const saveCropBtn = document.getElementById("saveCropBtn");
    const cancelCropBtn = document.getElementById("cancelCropBtn");
    const cropCanvas = document.getElementById("cropCanvas");
    const ctx = cropCanvas.getContext("2d");
    const displaySelect = document.getElementById("displaySelect");
    const logContainer = document.getElementById("logContainer");
    const dataContainer = document.getElementById("dataContainer");
    const autoScrollCb = document.getElementById("autoScroll");
    const clearLogBtn = document.getElementById("clearLogBtn");

    let isCropping = false;
    let stale = false;
    let hasReceivedFrame = false;
    let scriptsList = [];
    let cropRect = null; // { left, top, right, bottom } in stage pixels
    let dragMode = "None"; // "Create" or DragHandle
    let dragStart = { x: 0, y: 0 };
    let initialRect = null;
    const HANDLE_RADIUS = 8;

    let jmuxer = null;

    frameEl.addEventListener("timeupdate", () => {
      if (frameEl.buffered.length > 0) {
        const end = frameEl.buffered.end(frameEl.buffered.length - 1);
        const delay = end - frameEl.currentTime;
        // Catch-up logic for low latency without seeking (which snaps to keyframes)
        if (delay > 0.3) {
          frameEl.playbackRate = 2.0;
        } else if (delay > 0.15) {
          frameEl.playbackRate = 1.5;
        } else if (delay > 0.05) {
          frameEl.playbackRate = 1.1;
        } else {
          frameEl.playbackRate = 1.0;
        }
      }
    });

    function initJmuxer() {
      try {
        if (jmuxer) jmuxer.destroy();
        jmuxer = new JMuxer({
          node: "frame",
          mode: "video",
          flushingTime: 1,
          fps: 60,
          clearBuffer: true,
          debug: false
        });
      } catch (e) {
        vscode.postMessage({ type: "error", message: "initJmuxer failed: " + (e.stack || e.toString()) });
      }
    }
    initJmuxer();

    stopButton.addEventListener("click", () => {
      vscode.postMessage({ type: "stop" });
    });

    displaySelect.addEventListener("change", (e) => {
      vscode.postMessage({ type: "switchDisplay", displayId: parseInt(e.target.value, 10) });
    });

    clearLogBtn.addEventListener("click", () => {
      logContainer.innerHTML = "";
    });

    cropBtn.addEventListener("click", () => {
      startCropping();
    });

    cancelCropBtn.addEventListener("click", () => {
      stopCropping();
    });

    saveCropBtn.addEventListener("click", () => {
      saveCroppedTemplate();
    });

    function showOverlay(text) {
      overlayEl.textContent = text;
      overlayEl.hidden = false;
    }
    function hideOverlayIfConnected() {
      if (!stale) overlayEl.hidden = true;
    }

    function startCropping() {
      if (!hasReceivedFrame) return;
      isCropping = true;
      cropBtn.disabled = true;
      cropToolbar.hidden = false;
      cropCanvas.hidden = false;
      cropStatus.textContent = "";
      cropStatus.style.color = "var(--vscode-errorForeground)";
      resizeCanvas();
      
      // 預設一個中央 50% 範圍的裁切框
      const fit = getAspectFit();
      if (fit.width > 0 && fit.height > 0) {
        const w = fit.width * 0.4;
        const h = fit.height * 0.4;
        cropRect = {
          left: fit.left + (fit.width - w) / 2,
          top: fit.top + (fit.height - h) / 2,
          right: fit.left + (fit.width + w) / 2,
          bottom: fit.top + (fit.height + h) / 2,
        };
      } else {
        cropRect = null;
      }
      updateRoiDisplay();
      drawCropOverlay();
      vscode.postMessage({ type: "requestScripts" });
    }

    function stopCropping() {
      isCropping = false;
      cropBtn.disabled = !hasReceivedFrame;
      cropToolbar.hidden = true;
      cropCanvas.hidden = true;
      cropRect = null;
    }

    function resizeCanvas() {
      const rect = stage.getBoundingClientRect();
      cropCanvas.width = rect.width;
      cropCanvas.height = rect.height;
    }

    window.addEventListener("resize", () => {
      if (isCropping) {
        resizeCanvas();
        drawCropOverlay();
      }
    });

    function getAspectFit() {
      const stageRect = stage.getBoundingClientRect();
      const imgW = frameEl.videoWidth || 1;
      const imgH = frameEl.videoHeight || 1;
      const containerW = stageRect.width;
      const containerH = stageRect.height;
      
      if (containerW <= 0 || containerH <= 0) {
        return { left: 0, top: 0, width: 0, height: 0, scale: 1 };
      }

      const containerAspect = containerW / containerH;
      const imageAspect = imgW / imgH;

      let w, h, scale;
      if (containerAspect > imageAspect) {
        scale = containerH / imgH;
        w = imgW * scale;
        h = containerH;
      } else {
        scale = containerW / imgW;
        w = containerW;
        h = imgH * scale;
      }
      const left = (containerW - w) / 2;
      const top = (containerH - h) / 2;
      return { left, top, width: w, height: h, scale };
    }

    function norm(r) {
      if (!r) return null;
      return {
        left: Math.min(r.left, r.right),
        top: Math.min(r.top, r.bottom),
        right: Math.max(r.left, r.right),
        bottom: Math.max(r.top, r.bottom),
      };
    }

    function hitTest(r, x, y, radius) {
      if (!r) return "None";
      const nr = norm(r);
      const nearPoint = (px, py) => Math.hypot(x - px, y - py) < radius;
      const nearX = (vx) => Math.abs(x - vx) < radius;
      const nearY = (vy) => Math.abs(y - vy) < radius;

      if (nearPoint(nr.left, nr.top)) return "TopLeft";
      if (nearPoint(nr.right, nr.top)) return "TopRight";
      if (nearPoint(nr.left, nr.bottom)) return "BottomLeft";
      if (nearPoint(nr.right, nr.bottom)) return "BottomRight";
      if (nearX(nr.left) && y >= nr.top && y <= nr.bottom) return "Left";
      if (nearX(nr.right) && y >= nr.top && y <= nr.bottom) return "Right";
      if (nearY(nr.top) && x >= nr.left && x <= nr.right) return "Top";
      if (nearY(nr.bottom) && x >= nr.left && x <= nr.right) return "Bottom";
      if (x >= nr.left && x <= nr.right && y >= nr.top && y <= nr.bottom) return "Center";
      return "None";
    }

    function dragResize(r, handle, dx, dy) {
      switch (handle) {
        case "TopLeft": return { ...r, left: r.left + dx, top: r.top + dy };
        case "TopRight": return { ...r, top: r.top + dy, right: r.right + dx };
        case "BottomLeft": return { ...r, left: r.left + dx, bottom: r.bottom + dy };
        case "BottomRight": return { ...r, right: r.right + dx, bottom: r.bottom + dy };
        case "Top": return { ...r, top: r.top + dy };
        case "Bottom": return { ...r, bottom: r.bottom + dy };
        case "Left": return { ...r, left: r.left + dx };
        case "Right": return { ...r, right: r.right + dx };
        case "Center": return { left: r.left + dx, top: r.top + dy, right: r.right + dx, bottom: r.bottom + dy };
        default: return r;
      }
    }

    function drawCropOverlay() {
      ctx.clearRect(0, 0, cropCanvas.width, cropCanvas.height);
      if (!cropRect) return;
      const r = norm(cropRect);

      // 暗色半透明背景遮罩
      ctx.fillStyle = "rgba(0, 0, 0, 0.55)";
      ctx.fillRect(0, 0, cropCanvas.width, r.top);
      ctx.fillRect(0, r.top, r.left, r.bottom - r.top);
      ctx.fillRect(r.right, r.top, cropCanvas.width - r.right, r.bottom - r.top);
      ctx.fillRect(0, r.bottom, cropCanvas.width, cropCanvas.height - r.bottom);

      // 裁切框邊線
      ctx.strokeStyle = "#007acc";
      ctx.lineWidth = 2;
      ctx.strokeRect(r.left, r.top, r.right - r.left, r.bottom - r.top);

      // 8 個拖曳 handle
      ctx.fillStyle = "#ffffff";
      ctx.strokeStyle = "#007acc";
      ctx.lineWidth = 1.5;

      const midX = (r.left + r.right) / 2;
      const midY = (r.top + r.bottom) / 2;
      const handles = [
        [r.left, r.top], [midX, r.top], [r.right, r.top],
        [r.left, midY], [r.right, midY],
        [r.left, r.bottom], [midX, r.bottom], [r.right, r.bottom]
      ];

      for (const [hx, hy] of handles) {
        ctx.fillRect(hx - 4, hy - 4, 8, 8);
        ctx.strokeRect(hx - 4, hy - 4, 8, 8);
      }
    }

    function calculateRoi() {
      if (!cropRect) return null;
      const nr = norm(cropRect);
      const fit = getAspectFit();
      const bmW = frameEl.videoWidth || 1;
      const bmH = frameEl.videoHeight || 1;

      if (fit.scale <= 0) return null;

      const clampedLeft = Math.max(fit.left, Math.min(fit.left + fit.width, nr.left));
      const clampedRight = Math.max(fit.left, Math.min(fit.left + fit.width, nr.right));
      const clampedTop = Math.max(fit.top, Math.min(fit.top + fit.height, nr.top));
      const clampedBottom = Math.max(fit.top, Math.min(fit.top + fit.height, nr.bottom));

      const bmLeft = Math.round(Math.max(0, Math.min(bmW, (clampedLeft - fit.left) / fit.scale)));
      const bmRight = Math.round(Math.max(0, Math.min(bmW, (clampedRight - fit.left) / fit.scale)));
      const bmTop = Math.round(Math.max(0, Math.min(bmH, (clampedTop - fit.top) / fit.scale)));
      const bmBottom = Math.round(Math.max(0, Math.min(bmH, (clampedBottom - fit.top) / fit.scale)));

      const x = Math.min(bmLeft, bmRight);
      const y = Math.min(bmTop, bmBottom);
      const w = Math.abs(bmRight - bmLeft);
      const h = Math.abs(bmBottom - bmTop);

      return { x, y, w, h };
    }

    function updateRoiDisplay() {
      const roi = calculateRoi();
      if (roi && roi.w > 0 && roi.h > 0) {
        roiInfo.textContent = "ROI: [" + roi.x + ", " + roi.y + ", " + roi.w + ", " + roi.h + "] (" + roi.w + "x" + roi.h + " px)";
      } else {
        roiInfo.textContent = "";
      }
    }

    // Canvas 互動滑鼠事件
    cropCanvas.addEventListener("mousedown", (e) => {
      const rect = cropCanvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const hit = hitTest(cropRect, x, y, HANDLE_RADIUS);

      if (hit !== "None") {
        dragMode = hit;
        dragStart = { x, y };
        initialRect = { ...cropRect };
      } else {
        dragMode = "Create";
        dragStart = { x, y };
        cropRect = { left: x, top: y, right: x, bottom: y };
      }
      drawCropOverlay();
    });

    window.addEventListener("mousemove", (e) => {
      if (!isCropping || dragMode === "None") {
        if (isCropping) {
          const rect = cropCanvas.getBoundingClientRect();
          const x = e.clientX - rect.left;
          const y = e.clientY - rect.top;
          const hit = hitTest(cropRect, x, y, HANDLE_RADIUS);
          switch (hit) {
            case "TopLeft":
            case "BottomRight": cropCanvas.style.cursor = "nwse-resize"; break;
            case "TopRight":
            case "BottomLeft": cropCanvas.style.cursor = "nesw-resize"; break;
            case "Top":
            case "Bottom": cropCanvas.style.cursor = "ns-resize"; break;
            case "Left":
            case "Right": cropCanvas.style.cursor = "ew-resize"; break;
            case "Center": cropCanvas.style.cursor = "move"; break;
            default: cropCanvas.style.cursor = "crosshair"; break;
          }
        }
        return;
      }

      const rect = cropCanvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const dx = x - dragStart.x;
      const dy = y - dragStart.y;

      if (dragMode === "Create") {
        cropRect = {
          left: dragStart.x,
          top: dragStart.y,
          right: x,
          bottom: y,
        };
      } else {
        cropRect = dragResize(initialRect, dragMode, dx, dy);
      }
      updateRoiDisplay();
      drawCropOverlay();
    });

    window.addEventListener("mouseup", () => {
      if (dragMode !== "None") {
        dragMode = "None";
        cropRect = norm(cropRect);
        updateRoiDisplay();
        drawCropOverlay();
      }
    });

    function saveCroppedTemplate() {
      const scriptId = scriptSelect.value;
      const templateName = templateNameInput.value.trim();
      const roi = calculateRoi();

      if (!scriptId) {
        cropStatus.textContent = "請選擇目標腳本";
        return;
      }
      if (!templateName) {
        cropStatus.textContent = "請輸入模板名稱";
        return;
      }
      if (!roi || roi.w <= 0 || roi.h <= 0) {
        cropStatus.textContent = "請拉出有效的裁切區域";
        return;
      }

      // 擷取圖片畫布並輸出 PNG base64
      const helperCanvas = document.createElement("canvas");
      helperCanvas.width = roi.w;
      helperCanvas.height = roi.h;
      const hCtx = helperCanvas.getContext("2d");

      try {
        hCtx.drawImage(
          frameEl,
          roi.x, roi.y, roi.w, roi.h,
          0, 0, roi.w, roi.h
        );
        const dataUrl = helperCanvas.toDataURL("image/png");
        const pngBase64 = dataUrl.split(",")[1];

        cropStatus.style.color = "var(--vscode-foreground)";
        cropStatus.textContent = "儲存中…";
        saveCropBtn.disabled = true;

        vscode.postMessage({
          type: "saveTemplate",
          scriptId,
          templateName,
          roi,
          pngBase64,
        });
      } catch (err) {
        cropStatus.style.color = "var(--vscode-errorForeground)";
        cropStatus.textContent = "擷取畫面失敗: " + err.message;
        saveCropBtn.disabled = false;
      }
    }

    window.addEventListener("message", (event) => {
      const msg = event.data;
      if (msg.type === "state") {
        renderState(msg.state);
      } else if (msg.type === "frame") {
        let nalu = msg.data;
        if (!(nalu instanceof Uint8Array)) {
          nalu = new Uint8Array(nalu instanceof ArrayBuffer ? nalu : Object.values(nalu));
        }
        jmuxer.feed({ video: nalu });
        hasReceivedFrame = true;
        if (!isCropping) {
          cropBtn.disabled = false;
        }
        hideOverlayIfConnected();
      } else if (msg.type === "staleness") {
        stale = msg.stale;
        if (stale) {
          showOverlay("畫面已停滯——裝置可能休眠、WiFi 斷線，或 workbench service 已停止");
        } else {
          hideOverlayIfConnected();
        }
      } else if (msg.type === "scripts") {
        scriptsList = msg.scripts || [];
        const currentVal = scriptSelect.value;
        scriptSelect.innerHTML = "";
        for (const s of scriptsList) {
          const opt = document.createElement("option");
          opt.value = s.id;
          opt.textContent = s.name ? (s.name + " (" + s.id + ")") : s.id;
          scriptSelect.appendChild(opt);
        }
        if (currentVal && scriptsList.some(s => s.id === currentVal)) {
          scriptSelect.value = currentVal;
        }
      } else if (msg.type === "displays") {
        displaySelect.innerHTML = "";
        msg.displays.forEach(d => {
          const opt = document.createElement("option");
          opt.value = d.id;
          opt.textContent = \`\${d.name} (\${d.width}x\${d.height})\`;
          if (d.id === msg.currentDisplayId) opt.selected = true;
          displaySelect.appendChild(opt);
        });
      } else if (msg.type === "streamEvent") {
        handleStreamEvent(msg.event);
      } else if (msg.type === "saveTemplateResult") {
        saveCropBtn.disabled = false;
        if (msg.success) {
          cropStatus.style.color = "#4ec9b0";
          cropStatus.textContent = "儲存成功！";
          setTimeout(() => {
            stopCropping();
          }, 800);
        } else {
          cropStatus.style.color = "var(--vscode-errorForeground)";
          cropStatus.textContent = msg.error || "儲存失敗";
        }
      }
    });

    function renderState(state) {
      switch (state.status) {
        case "disconnected":
          statusEl.textContent = "已停止";
          showOverlay("串流已停止");
          cropBtn.disabled = true;
          break;
        case "connecting":
          statusEl.textContent = "連線中 display " + state.displayId + "…";
          showOverlay("連線中…");
          cropBtn.disabled = true;
          break;
        case "connected":
          statusEl.textContent = "已連線 display " + state.displayId;
          break;
        case "error":
          statusEl.textContent = "連線失敗：" + state.message;
          showOverlay("連線失敗：" + state.message);
          cropBtn.disabled = true;
          break;
      }
    }

    let lastData = {};
    function handleStreamEvent(e) {
      if (e.case === "log") {
        const div = document.createElement("div");
        div.className = "logLine";
        div.textContent = e.value;
        logContainer.appendChild(div);
        if (autoScrollCb.checked) {
          logContainer.scrollTop = logContainer.scrollHeight;
        }
      } else if (e.case === "data") {
        renderDataTree(dataContainer, e.value || {});
      }
    }

    function renderDataTree(container, data) {
      container.innerHTML = "";
      for (const [k, v] of Object.entries(data)) {
        const node = document.createElement("div");
        node.className = "tree-node";
        
        const keySpan = document.createElement("span");
        keySpan.className = "tree-key";
        keySpan.textContent = k + ": ";
        
        const valSpan = document.createElement("span");
        if (typeof v === "string") {
          valSpan.className = "tree-val-string";
          valSpan.textContent = '"' + v + '"';
        } else if (typeof v === "number") {
          valSpan.className = "tree-val-number";
          valSpan.textContent = v;
        } else if (typeof v === "boolean") {
          valSpan.className = "tree-val-boolean";
          valSpan.textContent = v;
        } else {
          valSpan.textContent = String(v);
        }
        
        if (lastData[k] !== v) {
          valSpan.classList.add("flash");
        }
        
        node.appendChild(keySpan);
        node.appendChild(valSpan);
        container.appendChild(node);
      }
      lastData = data;
    }
  </script>
</body>
</html>`;
}
