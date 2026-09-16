// ReLC VS Code Webview Adapter & Diagnostic Test Harness
// Polyfills `acquireVsCodeApi()` for standalone browser environments.
(function () {
  const urlParams = new URLSearchParams(window.location.search);
  let currentTarget = urlParams.get("target") || (window.location.hostname === "127.0.0.1" || window.location.hostname === "localhost" ? "192.168.68.110:8787" : window.location.host);
  let currentMode = urlParams.get("mode") || "device"; // "device" or "mock"
  let currentDisplayId = parseInt(urlParams.get("display") || "0", 10);

  let activeStreamAbort = null;
  let activeWs = null;
  let frameCount = 0;
  let lastFrameTime = performance.now();
  let fps = 0;
  let fpsEl = null;
  let frameCountEl = null;
  let modeSelect = null;
  let targetInput = null;
  let connectBtn = null;
  let toggleMirrorBtn = null;

  // 1. 注入頂部診斷與控制列
  function initHarnessToolbar() {
    if (document.getElementById("reLCTestHarnessBar")) return;
    const harnessBar = document.createElement("div");
    harnessBar.id = "reLCTestHarnessBar";
    harnessBar.innerHTML = `
    <style>
      #reLCTestHarnessBar {
        background: #181818;
        border-bottom: 2px solid #0e639c;
        padding: 6px 12px;
        display: flex;
        align-items: center;
        gap: 12px;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        font-size: 12px;
        color: #ddd;
        z-index: 1000;
        flex-shrink: 0;
      }
      #reLCTestHarnessBar .badge {
        background: #0e639c;
        color: #fff;
        padding: 2px 6px;
        border-radius: 3px;
        font-weight: bold;
        font-size: 11px;
      }
      #reLCTestHarnessBar select, #reLCTestHarnessBar input {
        background: #2d2d2d;
        color: #fff;
        border: 1px solid #444;
        padding: 3px 6px;
        border-radius: 3px;
      }
      #reLCTestHarnessBar button {
        background: #0e639c;
        color: #fff;
        border: none;
        padding: 4px 10px;
        border-radius: 3px;
        cursor: pointer;
      }
      #reLCTestHarnessBar button:hover {
        background: #1177bb;
      }
      #reLCTestHarnessBar .stats {
        margin-left: auto;
        display: flex;
        gap: 12px;
        font-family: monospace;
        color: #4ec9b0;
      }
    </style>
    <span class="badge">ReLC LLM Test Harness</span>
    <label>模式:
      <select id="thModeSelect">
        <option value="device">真機模式 (Real Device)</option>
        <option value="mock">模擬模式 (Mock Server)</option>
      </select>
    </label>
    <label>目標位址:
      <input type="text" id="thTargetInput" value="${currentTarget}" size="22" />
    </label>
    <button id="thConnectBtn">連線 (Connect)</button>
    <button id="thToggleMirrorBtn" style="background:#444;">切換實體鏡像 (Toggle Mirror)</button>
    <div class="stats">
      <span>FPS: <b id="thFps">0</b></span>
      <span>Frames: <b id="thFrameCount">0</b></span>
    </div>
  `;
    document.body.prepend(harnessBar);

    modeSelect = document.getElementById("thModeSelect");
    targetInput = document.getElementById("thTargetInput");
    connectBtn = document.getElementById("thConnectBtn");
    toggleMirrorBtn = document.getElementById("thToggleMirrorBtn");
    fpsEl = document.getElementById("thFps");
    frameCountEl = document.getElementById("thFrameCount");

    modeSelect.value = currentMode;
    targetInput.disabled = currentMode === "mock";

    modeSelect.addEventListener("change", () => {
      currentMode = modeSelect.value;
      targetInput.disabled = currentMode === "mock";
      reconnect();
    });

    connectBtn.addEventListener("click", () => {
      currentTarget = targetInput.value.trim();
      reconnect();
    });

    toggleMirrorBtn.addEventListener("click", async () => {
      try {
        toggleMirrorBtn.disabled = true;
        const base = getApiBase();
        const res = await fetch(`${base}/displays/${currentDisplayId}/mirror?enable=true`, { method: "POST" });
        const text = await res.text();
        alert(`鏡像控制回應: ${text}`);
        await refreshDisplays();
      } catch (e) {
        alert(`鏡像控制失敗: ${e.message}`);
      } finally {
        toggleMirrorBtn.disabled = false;
      }
    });
  }

  function getApiBase() {
    if (currentMode === "mock") {
      return `/mock`;
    }
    // 使用本地測試伺服器的反向代理以避免瀏覽器 CORS
    return `/proxy/${encodeURIComponent(currentTarget)}`;
  }

  function getWsUrl() {
    if (currentMode === "mock") {
      const loc = window.location;
      const proto = loc.protocol === "https:" ? "wss:" : "ws:";
      return `${proto}//${loc.host}/mock/ws`;
    }
    const loc = window.location;
    const proto = loc.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${loc.host}/proxy-ws?target=${encodeURIComponent(currentTarget)}`;
  }

  // 2. Mock VsCode API
  const mockVsCodeApi = {
    postMessage: async (msg) => {
      if (!msg) return;
      console.log("[TestHarness:postMessage]", msg);
      if (msg.type === "switchDisplay") {
        currentDisplayId = msg.displayId;
        connectStream(currentDisplayId);
      } else if (msg.type === "stop") {
        disconnectStream();
        window.postMessage({ type: "state", state: { status: "disconnected", displayId: currentDisplayId } }, "*");
      } else if (msg.type === "requestScripts") {
        try {
          const res = await fetch(`${getApiBase()}/scripts`);
          const scripts = res.ok ? await res.json() : [];
          window.postMessage({ type: "scripts", scripts }, "*");
        } catch {
          window.postMessage({ type: "scripts", scripts: [] }, "*");
        }
      } else if (msg.type === "saveTemplate") {
        try {
          const { scriptId, templateName, roi, pngBase64 } = msg;
          const url = `${getApiBase()}/scripts/${encodeURIComponent(scriptId)}/templates/${encodeURIComponent(templateName)}?x=${roi.x}&y=${roi.y}&w=${roi.w}&h=${roi.h}`;
          const bin = Uint8Array.from(atob(pngBase64), (c) => c.charCodeAt(0));
          const res = await fetch(url, { method: "PUT", body: bin });
          if (res.ok) {
            window.postMessage({ type: "saveTemplateResult", success: true }, "*");
          } else {
            const err = await res.text();
            window.postMessage({ type: "saveTemplateResult", success: false, error: err }, "*");
          }
        } catch (e) {
          window.postMessage({ type: "saveTemplateResult", success: false, error: e.message }, "*");
        }
      } else if (msg.type === "toggleMirror") {
        try {
          const enable = msg.enable !== false;
          await fetch(`${getApiBase()}/displays/${msg.displayId}/mirror?enable=${enable}`, { method: "POST" });
          await refreshDisplays();
          connectStream(msg.displayId);
        } catch (e) {
          console.error("toggleMirror error:", e);
        }
      }
    },
  };

  window.acquireVsCodeApi = () => mockVsCodeApi;
  window.vscode = mockVsCodeApi;

  // 3. 顯示器清單與串流管理
  async function refreshDisplays() {
    try {
      const res = await fetch(`${getApiBase()}/displays`);
      if (res.ok) {
        const displays = await res.json();
        window.postMessage({ type: "displays", displays, currentDisplayId }, "*");
      }
    } catch (e) {
      console.warn("fetch /displays failed:", e);
    }
  }

  function disconnectStream() {
    if (activeStreamAbort) {
      activeStreamAbort.abort();
      activeStreamAbort = null;
    }
  }

  async function connectStream(displayId) {
    disconnectStream();
    window.postMessage({ type: "state", state: { status: "connecting", displayId } }, "*");

    const abort = new AbortController();
    activeStreamAbort = abort;

    try {
      const url = `${getApiBase()}/mirror/${displayId}`;
      const res = await fetch(url, { signal: abort.signal });
      if (!res.ok) {
        window.postMessage(
          {
            type: "state",
            state: {
              status: "error",
              displayId,
              message: `HTTP ${res.status}${res.status === 404 ? "（該顯示器未開啟鏡像畫面；實體螢幕需先啟動鏡像）" : ""}`,
            },
          },
          "*"
        );
        return;
      }

      window.postMessage({ type: "state", state: { status: "connected", displayId } }, "*");

      // 讀取 MJPEG multipart stream
      const reader = res.body.getReader();
      let buffer = new Uint8Array(0);

      while (!abort.signal.aborted) {
        const { done, value } = await reader.read();
        if (done) break;

        const merged = new Uint8Array(buffer.length + value.length);
        merged.set(buffer);
        merged.set(value, buffer.length);
        buffer = merged;

        // 搜尋 JPEG SOI (0xFF, 0xD8) 與 EOI (0xFF, 0xD9)
        let soi = -1;
        for (let i = 0; i < buffer.length - 1; i++) {
          if (buffer[i] === 0xff && buffer[i + 1] === 0xd8) {
            soi = i;
            break;
          }
        }

        if (soi !== -1) {
          let eoi = -1;
          for (let i = soi + 2; i < buffer.length - 1; i++) {
            if (buffer[i] === 0xff && buffer[i + 1] === 0xd9) {
              eoi = i + 2;
              break;
            }
          }

          if (eoi !== -1) {
            const frameBytes = buffer.subarray(soi, eoi);
            buffer = buffer.subarray(eoi);

            // 轉為 Base64 Data URI
            let binary = "";
            const len = frameBytes.byteLength;
            for (let b = 0; b < len; b++) {
              binary += String.fromCharCode(frameBytes[b]);
            }
            const base64 = btoa(binary);
            const dataUri = "data:image/jpeg;base64," + base64;

            frameCount++;
            frameCountEl.textContent = frameCount;
            const now = performance.now();
            const delta = now - lastFrameTime;
            if (delta >= 500) {
              fps = Math.round((1000 / delta) * 10) / 10;
              fpsEl.textContent = fps;
              lastFrameTime = now;
            }

            window.postMessage({ type: "frame", dataUri }, "*");
          }
        }
      }
    } catch (err) {
      if (!abort.signal.aborted) {
        window.postMessage(
          {
            type: "state",
            state: { status: "error", displayId, message: err.message },
          },
          "*"
        );
      }
    }
  }

  function connectWs() {
    if (activeWs) {
      activeWs.close();
      activeWs = null;
    }
    try {
      const ws = new WebSocket(getWsUrl());
      activeWs = ws;
      ws.onmessage = (event) => {
        // Echo or StreamEvent
        try {
          const data = JSON.parse(event.data);
          window.postMessage({ type: "streamEvent", event: data }, "*");
        } catch {
          window.postMessage({ type: "streamEvent", event: { case: "log", value: event.data } }, "*");
        }
      };
    } catch (e) {
      console.warn("WebSocket connect failed:", e);
    }
  }

  function reconnect() {
    disconnectStream();
    refreshDisplays();
    connectStream(currentDisplayId);
    connectWs();
  }

  function init() {
    initHarnessToolbar();
    reconnect();
  }

  if (document.readyState === "loading") {
    window.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
