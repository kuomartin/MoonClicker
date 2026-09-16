// ReLC VS Code Webview Adapter & Diagnostic Test Harness
// Polyfills `acquireVsCodeApi()` for standalone browser environments.
(function () {
  // Local UI state — synced from /__dev__/config on init
  let currentMode = "mock";
  let currentTarget = "192.168.68.110:8787";
  let currentDisplayId = parseInt(new URLSearchParams(window.location.search).get("display") || "0", 10);

  let activeStreamAbort = null;
  let activeWs = null;
  let activeMirrorWs = null;
  let frameCount = 0;
  let lastFrameTime = performance.now();
  let fps = 0;
  let fpsEl = null;
  let frameCountEl = null;
  let modeSelect = null;
  let targetInput = null;
  let connectBtn = null;
  let toggleMirrorBtn = null;
  let pinInput = null;
  let pairBtn = null;
  let pairStatus = null;
  let authToken = null;

  // ── Harness toolbar ──────────────────────────────────────────────────────────
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
      #reLCTestHarnessBar button:hover { background: #1177bb; }
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
      <input type="text" id="thTargetInput" size="22" />
    </label>
    <button id="thConnectBtn">連線 (Connect)</button>
    <span id="thPairSection" style="display:flex;align-items:center;gap:6px;border-left:1px solid #444;padding-left:12px;">
      <label style="white-space:nowrap;">PIN:
        <input type="text" id="thPinInput" placeholder="123456" size="8" maxlength="10"
          style="letter-spacing:2px;font-family:monospace;width:70px;" />
      </label>
      <button id="thPairBtn" style="background:#2d7a2d;">配對 (Pair)</button>
      <span id="thPairStatus" style="font-size:13px;"></span>
    </span>
    <a href="/__dev__/mdns" target="_blank"
      style="color:#4ec9b0;font-size:11px;text-decoration:none;border-left:1px solid #444;padding-left:12px;">
      🔍 mDNS
    </a>
    <button id="thToggleMirrorBtn" style="background:#444;">切換實體鏡像</button>
    <div class="stats">
      <span>FPS: <b id="thFps">0</b></span>
      <span>Frames: <b id="thFrameCount">0</b></span>
    </div>
  `;
    document.body.prepend(harnessBar);

    modeSelect    = document.getElementById("thModeSelect");
    targetInput   = document.getElementById("thTargetInput");
    connectBtn    = document.getElementById("thConnectBtn");
    toggleMirrorBtn = document.getElementById("thToggleMirrorBtn");
    pinInput      = document.getElementById("thPinInput");
    pairBtn       = document.getElementById("thPairBtn");
    pairStatus    = document.getElementById("thPairStatus");
    fpsEl         = document.getElementById("thFps");
    frameCountEl  = document.getElementById("thFrameCount");

    modeSelect.addEventListener("change", async () => {
      await postConfig({ mode: modeSelect.value, target: targetInput.value.trim() });
      clearToken();
      updatePairSection();
      reconnect();
    });

    connectBtn.addEventListener("click", async () => {
      await postConfig({ mode: modeSelect.value, target: targetInput.value.trim() });
      clearToken();
      reconnect();
    });

    pairBtn.addEventListener("click", async () => {
      const pin = pinInput.value.trim();
      if (!pin) { alert("請輸入 PIN 碼"); return; }
      pairBtn.disabled = true;
      pairStatus.textContent = "⏳";
      try {
        const res = await authorizedFetch("/pair", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ pin })
        });
        if (res.ok) {
          const data = await res.json();
          authToken = data.token;
          pairStatus.textContent = "✅ 已配對";
          pairStatus.style.color = "#4ec9b0";
          reconnect();
        } else {
          const msg = await res.text();
          pairStatus.textContent = `❌ ${msg || res.status}`;
          pairStatus.style.color = "#f48771";
        }
      } catch (e) {
        pairStatus.textContent = `❌ ${e.message}`;
        pairStatus.style.color = "#f48771";
      } finally {
        pairBtn.disabled = false;
      }
    });

    pinInput.addEventListener("keydown", (e) => { if (e.key === "Enter") pairBtn.click(); });

    toggleMirrorBtn.addEventListener("click", async () => {
      try {
        toggleMirrorBtn.disabled = true;
        const res = await authorizedFetch(`/displays/${currentDisplayId}/mirror?enable=true`, { method: "POST" });
        alert(`鏡像控制回應: ${await res.text()}`);
        await refreshDisplays();
      } catch (e) {
        alert(`鏡像控制失敗: ${e.message}`);
      } finally {
        toggleMirrorBtn.disabled = false;
      }
    });
  }

  // ── Config sync ──────────────────────────────────────────────────────────────
  async function loadConfig() {
    try {
      const res = await fetch("/__dev__/config");
      if (!res.ok) return;
      const cfg = await res.json();
      currentMode   = cfg.mode   || currentMode;
      currentTarget = cfg.target || currentTarget;
      if (modeSelect)   modeSelect.value        = currentMode;
      if (targetInput)  targetInput.value        = currentTarget;
      updatePairSection();
    } catch { /* server not yet ready */ }
  }

  async function postConfig(patch) {
    try {
      const res = await fetch("/__dev__/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(patch)
      });
      if (!res.ok) return;
      const cfg = await res.json();
      currentMode   = cfg.mode;
      currentTarget = cfg.target;
    } catch { /* ignore */ }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────
  function updatePairSection() {
    const section = document.getElementById("thPairSection");
    if (!section) return;
    const isDevice = currentMode === "device";
    section.style.display = isDevice ? "flex" : "none";
    if (targetInput) targetInput.disabled = !isDevice;
  }

  function clearToken() {
    authToken = null;
    if (pairStatus) pairStatus.textContent = "";
  }

  function authorizedFetch(url, options = {}) {
    if (authToken) {
      options.headers = Object.assign({}, options.headers, { "Authorization": `Bearer ${authToken}` });
    }
    return fetch(url, options);
  }

  function getWsUrl(subPath = "") {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${location.host}${subPath}`;
  }

  // ── Mock VS Code API ─────────────────────────────────────────────────────────
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
          const res = await authorizedFetch("/scripts");
          const scripts = res.ok ? await res.json() : [];
          window.postMessage({ type: "scripts", scripts }, "*");
        } catch {
          window.postMessage({ type: "scripts", scripts: [] }, "*");
        }
      } else if (msg.type === "saveTemplate") {
        try {
          const { scriptId, templateName, roi, pngBase64 } = msg;
          const url = `/scripts/${encodeURIComponent(scriptId)}/templates/${encodeURIComponent(templateName)}?x=${roi.x}&y=${roi.y}&w=${roi.w}&h=${roi.h}`;
          const bin = Uint8Array.from(atob(pngBase64), (c) => c.charCodeAt(0));
          const res = await authorizedFetch(url, { method: "PUT", body: bin });
          if (res.ok) {
            window.postMessage({ type: "saveTemplateResult", success: true }, "*");
          } else {
            window.postMessage({ type: "saveTemplateResult", success: false, error: await res.text() }, "*");
          }
        } catch (e) {
          window.postMessage({ type: "saveTemplateResult", success: false, error: e.message }, "*");
        }
      } else if (msg.type === "toggleMirror") {
        try {
          const enable = msg.enable !== false;
          await authorizedFetch(`/displays/${msg.displayId}/mirror?enable=${enable}`, { method: "POST" });
          await refreshDisplays();
          connectStream(msg.displayId);
        } catch (e) {
          console.error("toggleMirror error:", e);
        }
      } else if (msg.type === "refreshDisplays") {
        await refreshDisplays();
      }
    },
  };

  window.acquireVsCodeApi = () => mockVsCodeApi;
  window.vscode = mockVsCodeApi;

  // ── Displays & stream management ─────────────────────────────────────────────
  async function refreshDisplays() {
    try {
      const res = await authorizedFetch("/displays");
      if (res.ok) {
        const displays = await res.json();
        window.postMessage({ type: "displays", displays, currentDisplayId }, "*");
      }
    } catch (e) {
      console.warn("fetch /displays failed:", e);
    }
  }

  function disconnectStream() {
    if (activeMirrorWs) { activeMirrorWs.close(); activeMirrorWs = null; }
    if (activeStreamAbort) { activeStreamAbort.abort(); activeStreamAbort = null; }
  }

  async function connectStream(displayId) {
    disconnectStream();
    window.postMessage({ type: "state", state: { status: "connecting", displayId } }, "*");

    if (currentMode === "mock") {
      activeStreamAbort = new AbortController();
      window.postMessage({ type: "state", state: { status: "connected", displayId } }, "*");
      return;
    }

    // Device mode: WebSocket H.264 stream
    let wsUrl = getWsUrl(`/mirror/h264/${displayId}`);
    if (authToken) wsUrl += `${wsUrl.includes("?") ? "&" : "?"}token=${encodeURIComponent(authToken)}`;
    const ws = new WebSocket(wsUrl);
    ws.binaryType = "arraybuffer";
    activeMirrorWs = ws;

    ws.onopen = () => {
      if (activeMirrorWs !== ws) return;
      window.postMessage({ type: "state", state: { status: "connected", displayId } }, "*");
    };
    ws.onmessage = (event) => {
      if (activeMirrorWs !== ws) return;
      window.postMessage({ type: "frame", data: new Uint8Array(event.data) }, "*");
      frameCount++;
      if (frameCountEl) frameCountEl.textContent = frameCount;
      const now = performance.now();
      const delta = now - lastFrameTime;
      if (delta >= 500) {
        fps = Math.round((1000 / delta) * 10) / 10;
        if (fpsEl) fpsEl.textContent = fps;
        lastFrameTime = now;
      }
    };
    ws.onerror = () => {
      if (activeMirrorWs !== ws) return;
      window.postMessage({
        type: "state",
        state: { status: "error", displayId, message: "WebSocket 連線失敗（實體螢幕需先啟動鏡像）" }
      }, "*");
    };
    ws.onclose = () => {
      if (activeMirrorWs !== ws) return;
      window.postMessage({ type: "state", state: { status: "disconnected", displayId } }, "*");
    };
  }

  function connectWs() {
    if (activeWs) { activeWs.close(); activeWs = null; }
    try {
      const ws = new WebSocket(getWsUrl("/ws"));
      activeWs = ws;
      ws.onmessage = (event) => {
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

  async function init() {
    initHarnessToolbar();
    await loadConfig(); // sync UI from server config
    reconnect();
  }

  if (document.readyState === "loading") {
    window.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
