// MoonClicker Webview Mirror Panel Client Script
(function () {
  const vscode = window.vscode || (typeof acquireVsCodeApi === "function" ? acquireVsCodeApi() : null);

  // ---------- 共用元素 ----------
  const displaySelect = document.getElementById("displaySelect");
  const refreshDisplayBtn = document.getElementById("refreshDisplayBtn");
  const frameEl = document.getElementById("frame");

  // ---------- 模式切換 ----------
  const modeBtns = Array.from(document.querySelectorAll(".modeBtn"));
  const modePanels = {
    capture: document.getElementById("panel-capture"),
    build: document.getElementById("panel-build"),
    test: document.getElementById("panel-test"),
    write: document.getElementById("panel-write"),
    run: document.getElementById("panel-run"),
  };
  const runStage = document.getElementById("stage");
  const runOverlay = document.getElementById("overlay");
  const testStage = document.getElementById("testStage");
  const captureStage = document.getElementById("captureStage");
  const captureStageHint = document.getElementById("captureStageHint");
  let currentMode = "run";

  function setMode(mode) {
    if (!modePanels[mode] || mode === currentMode) return;
    if (currentMode === "test" && roiEditing) setRoiEditing(false);
    if (currentMode === "test" && mode !== "test") stopTestIfRunningOnModeExit();
    modePanels[currentMode].hidden = true;
    modePanels[mode].hidden = false;
    modeBtns.forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
    currentMode = mode;

    // #frame 是唯一一支真正接收串流的 <video>，在「運行除錯」／「測試模板」／「採集」之間搬移，
    // 而不是各建立一支——重建會讓 JMuxer 的 MediaSource 附件失效。
    if (mode === "test") {
      testStage.insertBefore(frameEl, testStage.firstChild);
      resizeRoiCanvas();
      drawRoiOverlay();
    } else if (mode === "capture") {
      captureStage.insertBefore(frameEl, captureStageHint);
    } else if (mode === "run") {
      runStage.insertBefore(frameEl, runOverlay);
    }
    if (mode === "capture") captureStageHint.hidden = hasReceivedFrame;
    if (mode === "build") {
      renderBuildShotPicker();
      requestTemplatesFor(buildScriptSelect.value);
    }
    if (mode === "write") renderWriteScriptCards();
    if (mode === "test") {
      requestTemplatesFor(testScriptSelect.value);
      renderTestTemplateOptions();
    }
  }

  modeBtns.forEach((btn) => {
    btn.addEventListener("click", () => setMode(btn.dataset.mode));
  });

  // ==================================================================
  // 5 · 運行除錯 —— 既有真實鏡像 / log / data 功能，原樣保留
  // ==================================================================
  const statusEl = document.getElementById("status");
  const stopButton = document.getElementById("stopButton");
  const logContainer = document.getElementById("logContainer");
  const dataContainer = document.getElementById("dataContainer");
  const autoScrollCb = document.getElementById("autoScroll");
  const clearLogBtn = document.getElementById("clearLogBtn");
  const toggleMirrorBtn = document.getElementById("toggleMirrorBtn");
  const overlayTextEl = document.getElementById("overlayText") || runOverlay;
  const overlayActionBtn = document.getElementById("overlayActionBtn");

  let stale = false;
  let hasReceivedFrame = false;
  let receivedFramesCount = 0;
  let scriptsList = [];
  let currentDisplays = [];
  let currentDisplayId = null;

  const jmuxer = typeof JMuxer !== "undefined" ? new JMuxer({
    node: "frame",
    mode: "video",
    flushingTime: 0,
    fps: 60,
    debug: false,
    onError: function (data) {
      vscode?.postMessage({ type: "error", message: "JMuxer error: " + JSON.stringify(data) });
    },
  }) : null;

  stopButton.addEventListener("click", () => {
    vscode?.postMessage({ type: "stop" });
  });

  displaySelect.addEventListener("change", (e) => {
    currentDisplayId = parseInt(e.target.value, 10);
    updateToolbarMirrorState();
    vscode?.postMessage({ type: "switchDisplay", displayId: currentDisplayId });
  });

  refreshDisplayBtn?.addEventListener("click", () => {
    vscode?.postMessage({ type: "refreshDisplays" });
  });

  clearLogBtn.addEventListener("click", () => {
    logContainer.innerHTML = "";
  });

  function updateToolbarMirrorState() {
    if (!toggleMirrorBtn) return;
    const current = currentDisplays.find((d) => d.id === currentDisplayId);
    if (current && current.isVirtual === false) {
      toggleMirrorBtn.hidden = false;
      toggleMirrorBtn.disabled = false;
      if (current.isMirrorActive) {
        toggleMirrorBtn.textContent = "停止鏡像";
        toggleMirrorBtn.className = "secondary";
      } else {
        toggleMirrorBtn.textContent = "啟動鏡像";
        toggleMirrorBtn.className = "";
      }
    } else {
      toggleMirrorBtn.hidden = true;
    }
  }

  function triggerMirrorToggle(enable) {
    if (currentDisplayId === null) return;
    if (toggleMirrorBtn) {
      toggleMirrorBtn.disabled = true;
      toggleMirrorBtn.textContent = enable ? "啟動中…" : "停止中…";
    }
    if (overlayActionBtn) {
      overlayActionBtn.disabled = true;
      overlayActionBtn.textContent = enable ? "啟動中…" : "停止中…";
    }
    vscode?.postMessage({ type: "toggleMirror", displayId: currentDisplayId, enable });
  }

  toggleMirrorBtn?.addEventListener("click", () => {
    const current = currentDisplays.find((d) => d.id === currentDisplayId);
    const targetEnable = current ? !current.isMirrorActive : true;
    triggerMirrorToggle(targetEnable);
  });

  overlayActionBtn?.addEventListener("click", () => {
    triggerMirrorToggle(true);
  });

  function showOverlay(text, showAction = false) {
    if (overlayTextEl) {
      overlayTextEl.textContent = text;
    } else {
      runOverlay.textContent = text;
    }
    if (overlayActionBtn) {
      const current = currentDisplays.find((d) => d.id === currentDisplayId);
      const isPhysical = current ? current.isVirtual === false : false;
      const isMirrorOff = current ? !current.isMirrorActive : false;
      const needsAction = showAction || isMirrorOff || (text && (text.includes("404") || text.includes("實體螢幕需先啟動鏡像")));
      overlayActionBtn.hidden = !(needsAction && isPhysical);
      overlayActionBtn.disabled = false;
      overlayActionBtn.textContent = "立即啟動實體螢幕鏡像";
    }
    runOverlay.hidden = false;
  }
  function hideOverlayIfConnected() {
    if (!stale) {
      runOverlay.hidden = true;
      if (overlayActionBtn) overlayActionBtn.hidden = true;
    }
  }

  function renderState(state) {
    if (!state) return;
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

  let lastData = {};
  function handleStreamEvent(e) {
    if (!e) return;
    if (e.case === "log" || e.log) {
      const line = e.value || e.log;
      const div = document.createElement("div");
      div.className = "logLine";
      div.textContent = line;
      logContainer.appendChild(div);
      if (autoScrollCb.checked) {
        logContainer.scrollTop = logContainer.scrollHeight;
      }
    } else if (e.case === "data" || e.data) {
      const data = e.value || e.data || {};
      renderDataTree(dataContainer, data);
      if (currentMode === "test" && testRunning && Object.prototype.hasOwnProperty.call(data, "visionTest")) {
        renderVisionTestResult(data.visionTest);
      }
    }
  }

  // 裝置端 `data.set("visionTest", ...)` 回報的實際比對結果（見 docs/lua-api.md `data`）——
  // 不是延遲量測，intervalMs 只是配置的輪詢間隔，見 testLatency 的標示文字。
  function renderVisionTestResult(v) {
    // `data.set(key, table)` 在 Lua 端「自動序列化為 JSON」（docs/lua-api.md `data`），送到
    // webview 這邊 e.value.visionTest 拿到的是一個 JSON 字串（例如 '{"hit":false}'），不是
    // 已經解好的物件——之前少了這一步 JSON.parse，畫面永遠卡在「等待結果…」，因為
    // typeof v === "string" 直接被下面的物件檢查擋掉。
    if (typeof v === "string") {
      try {
        v = JSON.parse(v);
      } catch {
        return;
      }
    }
    if (!v || typeof v !== "object") return;
    if (v.hit) {
      const confidence = typeof v.confidence === "number" ? v.confidence.toFixed(2) : "?";
      testMatchText.textContent = "命中 · 信心度 " + confidence + "（cx=" + v.cx + ", cy=" + v.cy + "）";
      testMatchIcon.textContent = "✓";
      testMatchIcon.className = "matchIcon hit";
    } else {
      testMatchText.textContent = "未命中";
      testMatchIcon.textContent = "✕";
      testMatchIcon.className = "matchIcon miss";
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
      if (lastData[k] !== v) valSpan.classList.add("flash");
      node.appendChild(keySpan);
      node.appendChild(valSpan);
      container.appendChild(node);
    }
    lastData = data;
  }

  // Console / Data 分頁
  document.querySelectorAll(".rightTabBtn").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".rightTabBtn").forEach((b) => b.classList.toggle("active", b === btn));
      document.getElementById("consoleTabPanel").hidden = btn.dataset.tab !== "console";
      document.getElementById("dataTabPanel").hidden = btn.dataset.tab !== "data";
    });
  });

  window.addEventListener("message", (event) => {
    const msg = event.data;
    if (!msg) return;
    if (msg.type === "state") {
      renderState(msg.state);
    } else if (msg.type === "frame") {
      if (jmuxer && msg.data) {
        let nalu = msg.data;
        if (!(nalu instanceof Uint8Array)) {
          nalu = new Uint8Array(nalu instanceof ArrayBuffer ? nalu : Object.values(nalu));
        }
        jmuxer.feed({ video: nalu });
        if (frameEl && frameEl.buffered && frameEl.buffered.length > 0) {
          const end = frameEl.buffered.end(frameEl.buffered.length - 1);
          const diff = end - frameEl.currentTime;
          if (diff > 0.5) {
            frameEl.currentTime = end - 0.05;
          } else if (diff > 0.15) {
            frameEl.playbackRate = 1.1;
          } else {
            frameEl.playbackRate = 1.0;
          }
        }
      } else if (msg.dataUri) {
        frameEl.src = msg.dataUri;
      }
      hasReceivedFrame = true;
      receivedFramesCount++;
      hideOverlayIfConnected();
      if (currentMode === "capture") captureStageHint.hidden = true;
      captureShotBtn.disabled = false;
    } else if (msg.type === "staleness") {
      stale = msg.stale;
      if (stale) {
        showOverlay("畫面已停滯——裝置可能休眠、WiFi 斷線，或 workbench service 已停止");
      } else {
        hideOverlayIfConnected();
      }
    } else if (msg.type === "scripts") {
      scriptsList = msg.scripts || [];
      populateScriptSelects();
      if (currentMode === "write") renderWriteScriptCards();
    } else if (msg.type === "displays") {
      currentDisplays = msg.displays || [];
      if (typeof msg.currentDisplayId === "number") {
        currentDisplayId = msg.currentDisplayId;
      } else if (currentDisplayId === null && currentDisplays.length > 0) {
        currentDisplayId = currentDisplays[0].id;
      }
      displaySelect.innerHTML = "";
      currentDisplays.forEach((d) => {
        const opt = document.createElement("option");
        opt.value = d.id;
        let label = d.name + " (" + d.width + "x" + d.height + ")";
        if (d.isVirtual === false) {
          label += d.isMirrorActive ? " [實體·鏡像中]" : " [實體·未開啟]";
        }
        opt.textContent = label;
        if (d.id === currentDisplayId) opt.selected = true;
        displaySelect.appendChild(opt);
      });
      updateToolbarMirrorState();
    } else if (msg.type === "streamEvent") {
      handleStreamEvent(msg.event);
    } else if (msg.type === "visionTestResult") {
      testPending = false;
      if (msg.success) {
        testRunning = true;
        setTestStatusPill("running");
        testLatency.textContent = "輪詢間隔 " + VISION_TEST_INTERVAL_MS + " ms";
        testMatchText.textContent = "等待結果…";
      } else {
        testRunning = false;
        setTestStatusPill("error", msg.error || "啟動測試比對失敗");
      }
      updateTestStartStopBtn();
    } else if (msg.type === "runStopResult") {
      testPending = false;
      if (msg.success) {
        if (pendingRestart) {
          pendingRestart = false;
          requestStartVisionTest();
          return;
        }
        testRunning = false;
        setTestStatusPill("idle");
        resetTestMatchUi();
      } else {
        pendingRestart = false;
        setTestStatusPill("error", msg.error || "停止執行失敗");
      }
      updateTestStartStopBtn();
    } else if (msg.type === "saveTemplateResult") {
      buildSaveBtn.disabled = false;
      if (msg.success && pendingSave) {
        buildStatus.style.color = "var(--moon-success)";
        buildStatus.textContent = "已存到裝置";
        buildTemplateName.value = "";
        requestTemplatesFor(pendingSave.scriptId); // 重新拉裝置端的真實清單，而不是本地猜一筆
        setTimeout(() => { buildStatus.textContent = ""; }, 2000);
      } else {
        buildStatus.style.color = "var(--vscode-errorForeground)";
        buildStatus.textContent = msg.error || "儲存失敗";
      }
      pendingSave = null;
    } else if (msg.type === "templatesResult") {
      deviceTemplatesLoading[msg.scriptId] = false;
      if (msg.success) {
        deviceTemplates[msg.scriptId] = msg.templates || [];
      }
      if (buildScriptSelect.value === msg.scriptId) renderBuildTemplateList();
      if (testScriptSelect.value === msg.scriptId) renderTestTemplateOptions();
    } else if (msg.type === "deleteTemplateResult") {
      if (msg.success) {
        requestTemplatesFor(msg.scriptId); // 同上，讓裝置端的真實清單說了算
      } else {
        buildStatus.style.color = "var(--vscode-errorForeground)";
        buildStatus.textContent = msg.error || "刪除失敗";
      }
    } else if (msg.type === "createScriptResult") {
      addingScript = false;
      addScriptBtn.disabled = false;
      if (msg.success) {
        setWriteStatus("已建立", false);
        newScriptIdInput.value = "";
        setTimeout(() => { writeStatus.textContent = ""; }, 2000);
        // 新腳本清單本身由 mirrorPanel.ts 緊接著送一次 "scripts" 補上，這裡不用自己拼。
      } else {
        setWriteStatus(msg.error || "建立失敗", true);
      }
    }
  });

  // ==================================================================
  // 1 · 採集 —— 真的從 <video id="frame"> 目前解碼出的畫面擷取一張 PNG（webview 端 canvas
  // drawImage，跟舊版裁切模板同一招），佇列本身還是只存在 webview 狀態（vscode.setState），
  // 面板關閉重開會不見；真正落地成檔案要接 RPC，見底部說明。
  // ==================================================================
  const captureShotBtn = document.getElementById("captureShotBtn");
  const captureQueueItems = document.getElementById("captureQueueItems");
  const captureQueueCount = document.getElementById("captureQueueCount");
  const clearQueueBtn = document.getElementById("clearQueueBtn");

  let captureShots = restoreState().captureShots || []; // { id, time, dataUrl }
  let selectedShotId = captureShots.length > 0 ? captureShots[captureShots.length - 1].id : null;
  captureShotBtn.disabled = true;

  function addShot() {
    const w = frameEl.videoWidth;
    const h = frameEl.videoHeight;
    if (!hasReceivedFrame || !w || !h) return;
    const helperCanvas = document.createElement("canvas");
    helperCanvas.width = w;
    helperCanvas.height = h;
    helperCanvas.getContext("2d").drawImage(frameEl, 0, 0, w, h);
    const dataUrl = helperCanvas.toDataURL("image/png");

    const now = new Date();
    const time = now.toLocaleTimeString("zh-Hant-TW", { hour12: false });
    const shot = { id: "shot-" + now.getTime(), time, dataUrl, width: w, height: h };
    captureShots.push(shot);
    selectedShotId = shot.id;
    persistState();
    renderCaptureQueue();
    if (currentMode === "build") renderBuildShotPicker();
  }

  function renderCaptureQueue() {
    captureQueueCount.textContent = "擷取佇列 · " + captureShots.length + " 張";
    captureQueueItems.innerHTML = "";
    for (const shot of captureShots) {
      const item = document.createElement("div");
      item.className = "shotThumb" + (shot.id === selectedShotId ? " selected" : "");
      item.innerHTML = '<div class="shotThumbBox"></div><span class="shotThumbTime"></span>';
      item.querySelector(".shotThumbBox").style.backgroundImage = "url(" + shot.dataUrl + ")";
      item.querySelector(".shotThumbTime").textContent = shot.time;
      item.addEventListener("click", () => {
        selectedShotId = shot.id;
        renderCaptureQueue();
      });
      captureQueueItems.appendChild(item);
    }
  }

  captureShotBtn.addEventListener("click", addShot);
  clearQueueBtn.addEventListener("click", () => {
    captureShots = [];
    selectedShotId = null;
    persistState();
    renderCaptureQueue();
    renderBuildShotPicker();
  });

  function restoreState() {
    try {
      return vscode?.getState() || {};
    } catch {
      return {};
    }
  }
  function persistState() {
    try {
      vscode?.setState({ captureShots });
    } catch {
      // 佇列圖片較大時（許多張高解析度截圖）可能超過 setState 的序列化上限，
      // 失敗就只留在記憶體內，不擋住擷取本身。
    }
  }

  // ==================================================================
  // 2 · 建立模板 —— ROI 裁切是真的互動（對著選定的擷取畫面算像素座標，跟測試模板的
  // ROI 編輯同一套幾何算法）；「儲存模板」「刪除模板」「列出模板」都真的呼叫裝置既有／
  // 新增的 PUT / GET / DELETE /scripts/{id}/templates(/{name})。deviceTemplates 是
  // 裝置端清單的本地快取（key 是 scriptId），每次存檔／刪除成功都會重新拉一次，
  // 不維護自己的猜測——裝置端說了算。
  // ==================================================================
  const buildShotPicker = document.getElementById("buildShotPicker");
  const buildSourceTime = document.getElementById("buildSourceTime");
  const buildScriptSelect = document.getElementById("buildScriptSelect");
  const buildTemplateName = document.getElementById("buildTemplateName");
  const buildThreshold = document.getElementById("buildThreshold");
  const buildThresholdVal = document.getElementById("buildThresholdVal");
  const buildStatus = document.getElementById("buildStatus");
  const buildSaveBtn = document.getElementById("buildSaveBtn");
  const buildTemplateListLabel = document.getElementById("buildTemplateListLabel");
  const buildTemplateList = document.getElementById("buildTemplateList");
  const buildCropPreview = document.getElementById("buildCropPreview");
  const buildRoiCanvas = document.getElementById("buildRoiCanvas");
  const buildCtx = buildRoiCanvas.getContext("2d");

  let deviceTemplates = {}; // scriptId -> [{ name, roi }] | undefined（尚未拉過）
  let deviceTemplatesLoading = {}; // scriptId -> boolean
  let buildRoiRect = null; // { left, top, right, bottom } in buildCropPreview 像素座標
  let buildRoiDragMode = "None";
  let buildRoiDragStart = { x: 0, y: 0 };
  let buildRoiInitialRect = null;
  let pendingSave = null;
  const shotImageCache = {}; // shot.id -> Promise<HTMLImageElement>，避免每次存檔重新解碼同一張圖

  function requestTemplatesFor(scriptId) {
    if (!scriptId || deviceTemplatesLoading[scriptId]) return;
    deviceTemplatesLoading[scriptId] = true;
    vscode?.postMessage({ type: "requestTemplates", scriptId });
  }

  function currentBuildShot() {
    return captureShots.find((s) => s.id === selectedShotId) || null;
  }

  function getShotImage(shot) {
    if (!shotImageCache[shot.id]) {
      shotImageCache[shot.id] = new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error("圖片解碼失敗"));
        img.src = shot.dataUrl;
      });
    }
    return shotImageCache[shot.id];
  }

  function renderBuildShotPicker() {
    buildShotPicker.innerHTML = "";
    if (captureShots.length === 0) {
      buildShotPicker.innerHTML = '<span class="emptyHint">尚無擷取畫面，先到「1 · 採集」擷取一張</span>';
      buildSourceTime.textContent = "—";
      buildCropPreview.style.backgroundImage = "";
      buildRoiRect = null;
      buildCtx.clearRect(0, 0, buildRoiCanvas.width, buildRoiCanvas.height);
      return;
    }
    for (const shot of captureShots) {
      const item = document.createElement("div");
      item.className = "shotPickerItem" + (shot.id === selectedShotId ? " selected" : "");
      item.style.backgroundImage = "url(" + shot.dataUrl + ")";
      item.title = shot.time;
      item.addEventListener("click", () => {
        selectedShotId = shot.id;
        renderBuildShotPicker();
      });
      buildShotPicker.appendChild(item);
    }
    const active = captureShots.find((s) => s.id === selectedShotId) || captureShots[captureShots.length - 1];
    selectedShotId = active.id;
    buildSourceTime.textContent = active.time;
    buildCropPreview.style.backgroundImage = "url(" + active.dataUrl + ")";
    if (currentMode === "build") resetBuildRoi();
  }

  // ---------- ROI 幾何：跟「3 · 測試模板」共用 roiNorm/roiHitTest/roiDragResize，
  // 只有「畫面跟 canvas 對應到哪個容器」不同，所以 fit/resize/draw/calculate 各自一份。
  function buildAspectFit() {
    const shot = currentBuildShot();
    const rect = buildCropPreview.getBoundingClientRect();
    const imgW = (shot && shot.width) || 1;
    const imgH = (shot && shot.height) || 1;
    const containerW = rect.width;
    const containerH = rect.height;
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
    return { left: (containerW - w) / 2, top: (containerH - h) / 2, width: w, height: h, scale };
  }

  function resizeBuildRoiCanvas() {
    const rect = buildCropPreview.getBoundingClientRect();
    buildRoiCanvas.width = rect.width;
    buildRoiCanvas.height = rect.height;
  }

  function drawBuildRoiOverlay() {
    buildCtx.clearRect(0, 0, buildRoiCanvas.width, buildRoiCanvas.height);
    if (!buildRoiRect) return;
    const r = roiNorm(buildRoiRect);
    buildCtx.strokeStyle = "#4fc1ff";
    buildCtx.lineWidth = 2;
    buildCtx.setLineDash([6, 4]);
    buildCtx.strokeRect(r.left, r.top, r.right - r.left, r.bottom - r.top);
    buildCtx.setLineDash([]);
    buildCtx.fillStyle = "#ffffff";
    buildCtx.strokeStyle = "#4fc1ff";
    buildCtx.lineWidth = 1.5;
    const midX = (r.left + r.right) / 2;
    const midY = (r.top + r.bottom) / 2;
    const handles = [
      [r.left, r.top], [midX, r.top], [r.right, r.top],
      [r.left, midY], [r.right, midY],
      [r.left, r.bottom], [midX, r.bottom], [r.right, r.bottom],
    ];
    for (const [hx, hy] of handles) {
      buildCtx.fillRect(hx - 4, hy - 4, 8, 8);
      buildCtx.strokeRect(hx - 4, hy - 4, 8, 8);
    }
  }

  function calculateBuildRoi() {
    const shot = currentBuildShot();
    if (!buildRoiRect || !shot) return null;
    const nr = roiNorm(buildRoiRect);
    const fit = buildAspectFit();
    const bmW = shot.width || 1;
    const bmH = shot.height || 1;
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
    if (w <= 0 || h <= 0) return null;
    return { x, y, w, h };
  }

  function resetBuildRoi() {
    resizeBuildRoiCanvas();
    const fit = buildAspectFit();
    if (fit.width > 0 && fit.height > 0) {
      const w = fit.width * 0.3;
      const h = fit.height * 0.2;
      buildRoiRect = {
        left: fit.left + (fit.width - w) / 2,
        top: fit.top + (fit.height - h) / 2,
        right: fit.left + (fit.width + w) / 2,
        bottom: fit.top + (fit.height + h) / 2,
      };
    } else {
      buildRoiRect = null;
    }
    drawBuildRoiOverlay();
  }

  buildRoiCanvas.addEventListener("mousedown", (e) => {
    if (currentMode !== "build") return;
    const rect = buildRoiCanvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const hit = roiHitTest(buildRoiRect, x, y, ROI_HANDLE_RADIUS);
    if (hit !== "None") {
      buildRoiDragMode = hit;
      buildRoiDragStart = { x, y };
      buildRoiInitialRect = { ...buildRoiRect };
    } else {
      buildRoiDragMode = "Create";
      buildRoiDragStart = { x, y };
      buildRoiRect = { left: x, top: y, right: x, bottom: y };
    }
    drawBuildRoiOverlay();
  });

  window.addEventListener("mousemove", (e) => {
    if (currentMode !== "build") return;
    const rect = buildRoiCanvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    if (buildRoiDragMode === "None") {
      const hit = roiHitTest(buildRoiRect, x, y, ROI_HANDLE_RADIUS);
      const cursors = {
        TopLeft: "nwse-resize", BottomRight: "nwse-resize",
        TopRight: "nesw-resize", BottomLeft: "nesw-resize",
        Top: "ns-resize", Bottom: "ns-resize",
        Left: "ew-resize", Right: "ew-resize",
        Center: "move",
      };
      buildRoiCanvas.style.cursor = cursors[hit] || "crosshair";
      return;
    }
    const dx = x - buildRoiDragStart.x;
    const dy = y - buildRoiDragStart.y;
    if (buildRoiDragMode === "Create") {
      buildRoiRect = { left: buildRoiDragStart.x, top: buildRoiDragStart.y, right: x, bottom: y };
    } else {
      buildRoiRect = roiDragResize(buildRoiInitialRect, buildRoiDragMode, dx, dy);
    }
    drawBuildRoiOverlay();
  });

  window.addEventListener("mouseup", () => {
    if (buildRoiDragMode !== "None") {
      buildRoiDragMode = "None";
      buildRoiRect = roiNorm(buildRoiRect);
      drawBuildRoiOverlay();
    }
  });

  function populateScriptSelect(selectEl) {
    const prev = selectEl.value;
    selectEl.innerHTML = "";
    for (const s of scriptsList) {
      const opt = document.createElement("option");
      opt.value = s.id;
      opt.textContent = s.name || s.id;
      selectEl.appendChild(opt);
    }
    if (scriptsList.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "（尚未連線，無法列出腳本）";
      selectEl.appendChild(opt);
    } else if (prev && scriptsList.some((s) => s.id === prev)) {
      selectEl.value = prev;
    }
  }

  function populateScriptSelects() {
    populateScriptSelect(buildScriptSelect);
    populateScriptSelect(testScriptSelect);
    renderBuildTemplateList();
    requestTemplatesFor(buildScriptSelect.value);
    requestTemplatesFor(testScriptSelect.value);
    renderTestTemplateOptions();
  }

  function currentBuildScriptName() {
    const s = scriptsList.find((s) => s.id === buildScriptSelect.value);
    return s ? (s.name || s.id) : buildScriptSelect.value || "此腳本";
  }

  function renderBuildTemplateList() {
    const scriptId = buildScriptSelect.value;
    buildTemplateListLabel.textContent = currentBuildScriptName() + " 的模板";
    buildTemplateList.innerHTML = "";
    const list = deviceTemplates[scriptId];
    if (list === undefined) {
      buildTemplateList.innerHTML =
        '<span class="emptyHint">' + (deviceTemplatesLoading[scriptId] ? "讀取中…" : "尚未連線") + "</span>";
      return;
    }
    if (list.length === 0) {
      buildTemplateList.innerHTML = '<span class="emptyHint">尚無模板</span>';
      return;
    }
    for (const t of list) {
      const row = document.createElement("div");
      row.className = "templateRow";
      row.innerHTML =
        '<div class="templateRowThumb"></div>' +
        '<div class="templateRowInfo">' +
        '<span class="templateRowName"></span>' +
        '<span class="templateRowMeta"></span>' +
        "</div>" +
        '<button type="button" class="linkBtn" title="刪除模板">✕</button>';
      row.querySelector(".templateRowName").textContent = t.name;
      row.querySelector(".templateRowMeta").textContent = t.roi.w + " × " + t.roi.h;
      row.querySelector("button").addEventListener("click", () => {
        row.querySelector("button").disabled = true;
        vscode?.postMessage({ type: "deleteTemplate", scriptId, templateName: t.name });
      });
      buildTemplateList.appendChild(row);
    }
  }

  buildScriptSelect.addEventListener("change", () => {
    renderBuildTemplateList();
    requestTemplatesFor(buildScriptSelect.value);
  });
  buildThreshold.addEventListener("input", () => {
    buildThresholdVal.textContent = (buildThreshold.value / 100).toFixed(2);
  });

  buildSaveBtn.addEventListener("click", async () => {
    const scriptId = buildScriptSelect.value;
    const name = buildTemplateName.value.trim();
    const shot = currentBuildShot();
    if (!scriptId) {
      buildStatus.textContent = "請先選擇所屬腳本";
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    if (!name) {
      buildStatus.textContent = "請輸入模板名稱";
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    if (!shot) {
      buildStatus.textContent = "請先到「1 · 採集」擷取一張畫面";
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    const roi = calculateBuildRoi();
    if (!roi) {
      buildStatus.textContent = "請先拉出有效的裁切區域";
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }

    buildSaveBtn.disabled = true;
    buildStatus.style.color = "var(--vscode-foreground)";
    buildStatus.textContent = "裁切中…";
    pendingSave = { scriptId, name, threshold: Number(buildThreshold.value) / 100 };

    try {
      const img = await getShotImage(shot);
      const helperCanvas = document.createElement("canvas");
      helperCanvas.width = roi.w;
      helperCanvas.height = roi.h;
      helperCanvas.getContext("2d").drawImage(img, roi.x, roi.y, roi.w, roi.h, 0, 0, roi.w, roi.h);
      const dataUrl = helperCanvas.toDataURL("image/png");
      const pngBase64 = dataUrl.split(",")[1];
      buildStatus.textContent = "儲存中…";
      vscode?.postMessage({ type: "saveTemplate", scriptId, templateName: name, roi, pngBase64 });
    } catch (err) {
      pendingSave = null;
      buildSaveBtn.disabled = false;
      buildStatus.style.color = "var(--vscode-errorForeground)";
      buildStatus.textContent = "裁切畫面失敗：" + (err && err.message ? err.message : String(err));
    }
  });

  // ==================================================================
  // 3 · 測試模板 —— ROI 拖曳、開始/停止、比對結果都是真的（裝置端 vision-test 端點）；
  // 模板來源是 deviceTemplates（跟「2 · 建立模板」共用，見該區塊開頭的說明），選了
  // 哪個腳本就拉那個腳本的清單
  // ==================================================================
  const testScriptSelect = document.getElementById("testScriptSelect");
  const testTemplateSelect = document.getElementById("testTemplateSelect");
  const testThreshold = document.getElementById("testThreshold");
  const testThresholdVal = document.getElementById("testThresholdVal");
  const testAdjustRoiBtn = document.getElementById("testAdjustRoiBtn");
  const testRoiCanvas = document.getElementById("testRoiCanvas");
  const testCtx = testRoiCanvas.getContext("2d");
  const testMatchIcon = document.getElementById("testMatchIcon");
  const testMatchText = document.getElementById("testMatchText");
  const testSnippet = document.getElementById("testSnippet");
  const copySnippetBtn = document.getElementById("copySnippetBtn");
  const testStartStopBtn = document.getElementById("testStartStopBtn");
  const testStatusPill = document.getElementById("testStatusPill");
  const testLatency = document.getElementById("testLatency");
  const testErrorMsg = document.getElementById("testErrorMsg");

  const VISION_TEST_INTERVAL_MS = 300;
  const VISION_TEST_DEBOUNCE_MS = 500;
  let testRunning = false;
  let testPending = false; // start/stop RPC 進行中
  let testRestartTimer = null;

  let roiEditing = false;
  let roiRect = null; // { left, top, right, bottom } in testStage 像素座標
  let roiDragMode = "None";
  let roiDragStart = { x: 0, y: 0 };
  let roiInitialRect = null;
  const ROI_HANDLE_RADIUS = 8;

  function currentScriptTemplates() {
    return deviceTemplates[testScriptSelect.value] || [];
  }

  function renderTestTemplateOptions() {
    const prev = testTemplateSelect.value;
    const list = currentScriptTemplates();
    testTemplateSelect.innerHTML = "";
    if (list.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = deviceTemplates[testScriptSelect.value] === undefined
        ? "（讀取中…）"
        : "（此腳本尚無模板，先到「2 · 建立模板」建立一個）";
      testTemplateSelect.appendChild(opt);
      testThreshold.disabled = true;
    } else {
      testThreshold.disabled = false;
      for (const t of list) {
        const opt = document.createElement("option");
        opt.value = t.name;
        opt.textContent = t.name + "（" + t.roi.w + " × " + t.roi.h + "）";
        testTemplateSelect.appendChild(opt);
      }
      if (prev && list.some((t) => t.name === prev)) {
        testTemplateSelect.value = prev;
      }
    }
    resetTestMatchUi();
    updateSnippet();
  }

  testScriptSelect.addEventListener("change", () => {
    requestTemplatesFor(testScriptSelect.value);
    renderTestTemplateOptions();
  });

  testTemplateSelect.addEventListener("change", () => {
    resetTestMatchUi();
    updateSnippet();
    scheduleTestRestartIfRunning();
  });

  testThreshold.addEventListener("input", () => {
    testThresholdVal.textContent = (testThreshold.value / 100).toFixed(2);
    updateSnippet();
    scheduleTestRestartIfRunning();
  });

  function resetTestMatchUi() {
    testMatchIcon.textContent = "?";
    testMatchIcon.className = "matchIcon";
    const t = currentTestTemplate();
    if (!t) {
      testMatchText.textContent = "尚無模板可比對";
      return;
    }
    testMatchText.textContent = testRunning ? "等待結果…" : "尚未開始測試";
  }

  function currentTestTemplate() {
    return currentScriptTemplates().find((t) => t.name === testTemplateSelect.value) || null;
  }

  function currentTestTemplateName() {
    const t = currentTestTemplate();
    return t ? t.name : "template";
  }

  // 對應真正的 Lua API（見 docs/lua-api.md 的 `vision` / `input`），不是隨便編的介面。
  function updateSnippet() {
    const roi = calculateTestRoi() || { x: 96, y: 420, w: 130, h: 40 };
    const name = currentTestTemplateName();
    const threshold = (testThreshold.value / 100).toFixed(2);
    testSnippet.textContent =
      "local hit = vision.find({\n" +
      '  image = "' + name + '",\n' +
      "  roi = { x = " + roi.x + ", y = " + roi.y + ", w = " + roi.w + ", h = " + roi.h + " },\n" +
      "  threshold = " + threshold + ",\n" +
      "})\n" +
      "if hit then\n" +
      "  input.tap(hit.cx, hit.cy)\n" +
      "end";
  }

  copySnippetBtn.addEventListener("click", async () => {
    const text = testSnippet.textContent;
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); } catch {}
      document.body.removeChild(ta);
    }
    const original = copySnippetBtn.textContent;
    copySnippetBtn.textContent = "已複製！";
    setTimeout(() => { copySnippetBtn.textContent = original; }, 1200);
  });

  // ---------- 即時測試比對 start/stop（真實 RPC，共用 /run 執行槽） ----------

  function setTestStatusPill(state, detail) {
    testStatusPill.classList.remove("statusPill-error");
    switch (state) {
      case "idle":
        testStatusPill.innerHTML = '<span class="statusDot"></span>未開始';
        break;
      case "starting":
        testStatusPill.innerHTML = '<span class="statusDot"></span>啟動中…';
        break;
      case "running":
        testStatusPill.innerHTML = '<span class="statusDot"></span>即時比對中';
        break;
      case "stopping":
        testStatusPill.innerHTML = '<span class="statusDot"></span>停止中…';
        break;
      case "error":
        testStatusPill.classList.add("statusPill-error");
        testStatusPill.innerHTML = '<span class="statusDot"></span>錯誤';
        break;
    }
    if (detail) {
      testErrorMsg.textContent = detail;
      testErrorMsg.hidden = false;
    } else {
      testErrorMsg.hidden = true;
    }
  }

  function updateTestStartStopBtn() {
    testStartStopBtn.disabled = testPending;
    if (testPending) {
      testStartStopBtn.textContent = testRunning ? "停止中…" : "啟動中…";
    } else {
      testStartStopBtn.textContent = testRunning ? "停止測試" : "開始測試";
    }
  }

  function requestStartVisionTest() {
    const t = currentTestTemplate();
    if (!t) {
      setTestStatusPill("error", "尚無模板可測試");
      return;
    }
    if (currentDisplayId === null) {
      setTestStatusPill("error", "尚未選擇顯示器");
      return;
    }
    const roi = calculateTestRoi() || { x: 96, y: 420, w: 130, h: 40 };
    testPending = true;
    updateTestStartStopBtn();
    setTestStatusPill("starting");
    vscode?.postMessage({
      type: "startVisionTest",
      scriptId: testScriptSelect.value,
      displayId: currentDisplayId,
      image: t.name,
      roi,
      threshold: testThreshold.value / 100,
      intervalMs: VISION_TEST_INTERVAL_MS,
    });
  }

  function requestStopRun() {
    testPending = true;
    updateTestStartStopBtn();
    setTestStatusPill("stopping");
    vscode?.postMessage({ type: "stopRun" });
  }

  testStartStopBtn.addEventListener("click", () => {
    if (testPending) return;
    if (testRunning) {
      requestStopRun();
    } else {
      requestStartVisionTest();
    }
  });

  // ROI 拖曳／閾值調整時，若測試正在跑，Lua 迴圈無法動態改參數——debounce 後
  // 停止再以新參數重新啟動（同一個執行槽，必須先 stop 再 start，不可並行）。
  function scheduleTestRestartIfRunning() {
    if (!testRunning || testPending) return;
    if (testRestartTimer) clearTimeout(testRestartTimer);
    testRestartTimer = setTimeout(() => {
      testRestartTimer = null;
      if (!testRunning || testPending) return;
      testPending = true;
      updateTestStartStopBtn();
      setTestStatusPill("starting");
      vscode?.postMessage({ type: "stopRun" });
      // 對應的 startVisionTest 會在收到 runStopResult 後續發（見 message handler）
      pendingRestart = true;
    }, VISION_TEST_DEBOUNCE_MS);
  }
  let pendingRestart = false;

  // 離開「3 · 測試模板」模式時，不留孤兒 vision-test 迴圈在裝置端繼續跑。
  function stopTestIfRunningOnModeExit() {
    if (testRestartTimer) {
      clearTimeout(testRestartTimer);
      testRestartTimer = null;
    }
    pendingRestart = false;
    if (testRunning && !testPending) {
      requestStopRun();
    }
  }

  function setRoiEditing(on) {
    roiEditing = on;
    testAdjustRoiBtn.classList.toggle("active", on);
    testRoiCanvas.hidden = !on;
    if (on) {
      resizeRoiCanvas();
      if (!roiRect) {
        const fit = roiAspectFit();
        if (fit.width > 0 && fit.height > 0) {
          const w = fit.width * 0.35;
          const h = fit.height * 0.12;
          roiRect = {
            left: fit.left + (fit.width - w) / 2,
            top: fit.top + (fit.height - h) / 2,
            right: fit.left + (fit.width + w) / 2,
            bottom: fit.top + (fit.height + h) / 2,
          };
        }
      }
      drawRoiOverlay();
      updateSnippet();
    }
  }

  testAdjustRoiBtn.addEventListener("click", () => setRoiEditing(!roiEditing));

  function resizeRoiCanvas() {
    const rect = testStage.getBoundingClientRect();
    testRoiCanvas.width = rect.width;
    testRoiCanvas.height = rect.height;
  }

  window.addEventListener("resize", () => {
    if (currentMode === "test") {
      resizeRoiCanvas();
      drawRoiOverlay();
    } else if (currentMode === "build") {
      resetBuildRoi();
    }
  });

  function roiAspectFit() {
    const stageRect = testStage.getBoundingClientRect();
    const imgW = frameEl.videoWidth || frameEl.naturalWidth || 1;
    const imgH = frameEl.videoHeight || frameEl.naturalHeight || 1;
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
    return { left: (containerW - w) / 2, top: (containerH - h) / 2, width: w, height: h, scale };
  }

  function roiNorm(r) {
    if (!r) return null;
    return {
      left: Math.min(r.left, r.right),
      top: Math.min(r.top, r.bottom),
      right: Math.max(r.left, r.right),
      bottom: Math.max(r.top, r.bottom),
    };
  }

  function roiHitTest(r, x, y, radius) {
    if (!r) return "None";
    const nr = roiNorm(r);
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

  function roiDragResize(r, handle, dx, dy) {
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

  function drawRoiOverlay() {
    testCtx.clearRect(0, 0, testRoiCanvas.width, testRoiCanvas.height);
    if (!roiRect) return;
    const r = roiNorm(roiRect);
    testCtx.strokeStyle = "#4fc1ff";
    testCtx.lineWidth = 2;
    testCtx.setLineDash([6, 4]);
    testCtx.strokeRect(r.left, r.top, r.right - r.left, r.bottom - r.top);
    testCtx.setLineDash([]);
    testCtx.fillStyle = "#ffffff";
    testCtx.strokeStyle = "#4fc1ff";
    testCtx.lineWidth = 1.5;
    const midX = (r.left + r.right) / 2;
    const midY = (r.top + r.bottom) / 2;
    const handles = [
      [r.left, r.top], [midX, r.top], [r.right, r.top],
      [r.left, midY], [r.right, midY],
      [r.left, r.bottom], [midX, r.bottom], [r.right, r.bottom],
    ];
    for (const [hx, hy] of handles) {
      testCtx.fillRect(hx - 4, hy - 4, 8, 8);
      testCtx.strokeRect(hx - 4, hy - 4, 8, 8);
    }
  }

  function calculateTestRoi() {
    if (!roiRect) return null;
    const nr = roiNorm(roiRect);
    const fit = roiAspectFit();
    const bmW = frameEl.videoWidth || frameEl.naturalWidth || 1;
    const bmH = frameEl.videoHeight || frameEl.naturalHeight || 1;
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
    if (w <= 0 || h <= 0) return null;
    return { x, y, w, h };
  }

  testRoiCanvas.addEventListener("mousedown", (e) => {
    if (!roiEditing) return;
    const rect = testRoiCanvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const hit = roiHitTest(roiRect, x, y, ROI_HANDLE_RADIUS);
    if (hit !== "None") {
      roiDragMode = hit;
      roiDragStart = { x, y };
      roiInitialRect = { ...roiRect };
    } else {
      roiDragMode = "Create";
      roiDragStart = { x, y };
      roiRect = { left: x, top: y, right: x, bottom: y };
    }
    drawRoiOverlay();
  });

  window.addEventListener("mousemove", (e) => {
    if (!roiEditing) return;
    const rect = testRoiCanvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    if (roiDragMode === "None") {
      const hit = roiHitTest(roiRect, x, y, ROI_HANDLE_RADIUS);
      const cursors = {
        TopLeft: "nwse-resize", BottomRight: "nwse-resize",
        TopRight: "nesw-resize", BottomLeft: "nesw-resize",
        Top: "ns-resize", Bottom: "ns-resize",
        Left: "ew-resize", Right: "ew-resize",
        Center: "move",
      };
      testRoiCanvas.style.cursor = cursors[hit] || "crosshair";
      return;
    }
    const dx = x - roiDragStart.x;
    const dy = y - roiDragStart.y;
    if (roiDragMode === "Create") {
      roiRect = { left: roiDragStart.x, top: roiDragStart.y, right: x, bottom: y };
    } else {
      roiRect = roiDragResize(roiInitialRect, roiDragMode, dx, dy);
    }
    drawRoiOverlay();
    updateSnippet();
  });

  window.addEventListener("mouseup", () => {
    if (roiDragMode !== "None") {
      roiDragMode = "None";
      roiRect = roiNorm(roiRect);
      drawRoiOverlay();
      updateSnippet();
      scheduleTestRestartIfRunning();
    }
  });

  // ==================================================================
  // 4 · 編寫 —— 全部是真的：清單（含裝置回報的模板數／上次修改）、「在編輯器開啟」
  // （既有的 moonclicker.openScript 指令，整份 pull 進本機鏡像資料夾、掛成 workspace
  // folder，跟 Explorer 樹狀圖點腳本同一條路）、「新增腳本」（POST /scripts/{id}）。
  // ==================================================================
  const writeScriptCards = document.getElementById("writeScriptCards");
  const addScriptBtn = document.getElementById("addScriptBtn");
  const newScriptIdInput = document.getElementById("newScriptIdInput");
  const writeStatus = document.getElementById("writeStatus");
  const SCRIPT_ID_PATTERN = /^[a-z0-9._-]+$/;
  let addingScript = false;

  function formatModified(ms) {
    if (!ms) return "—";
    return new Date(ms).toLocaleString("zh-Hant-TW", { hour12: false });
  }

  function renderWriteScriptCards() {
    writeScriptCards.innerHTML = "";
    if (scriptsList.length === 0) {
      writeScriptCards.innerHTML = '<span class="emptyHint">尚未連線，無法列出腳本</span>';
      return;
    }
    for (const s of scriptsList) {
      const templateCount = typeof s.templateCount === "number" ? s.templateCount : (deviceTemplates[s.id] || []).length;
      const card = document.createElement("div");
      card.className = "scriptCard";
      card.innerHTML =
        '<svg class="scriptCardIcon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"></polyline><polyline points="8 6 2 12 8 18"></polyline></svg>' +
        '<div class="scriptCardInfo"><span class="scriptCardName"></span><span class="scriptCardMeta"></span></div>' +
        '<button type="button" class="secondary">在編輯器開啟</button>';
      card.querySelector(".scriptCardName").textContent = s.name || s.id;
      card.querySelector(".scriptCardMeta").textContent = templateCount + " 個模板 · 上次修改 " + formatModified(s.modifiedMs);
      card.querySelector("button").addEventListener("click", () => {
        vscode?.postMessage({ type: "openScriptInEditor", scriptId: s.id, scriptName: s.name });
      });
      writeScriptCards.appendChild(card);
    }
  }

  function setWriteStatus(text, isError) {
    writeStatus.style.color = isError ? "var(--vscode-errorForeground)" : "var(--moon-success)";
    writeStatus.textContent = text;
  }

  addScriptBtn.addEventListener("click", () => {
    if (addingScript) return;
    const id = newScriptIdInput.value.trim();
    if (!id) {
      setWriteStatus("請輸入腳本名稱", true);
      return;
    }
    if (id.startsWith(".") || !SCRIPT_ID_PATTERN.test(id)) {
      setWriteStatus("只能是小寫英數字、「.」「_」「-」，且不能以「.」開頭", true);
      return;
    }
    addingScript = true;
    addScriptBtn.disabled = true;
    writeStatus.style.color = "var(--vscode-foreground)";
    writeStatus.textContent = "建立中…";
    vscode?.postMessage({ type: "createScript", scriptId: id });
  });

  // ---------- 初始渲染 ----------
  renderCaptureQueue();
  populateScriptSelects();
  renderTestTemplateOptions();

  // 自動化與 LLM DevTools 測試掛載
  window.__MOONCLICKER_TEST__ = {
    getState: () => ({
      mode: currentMode,
      hasReceivedFrame,
      receivedFramesCount,
      stale,
      scriptsCount: scriptsList.length,
      displaysCount: displaySelect.options.length,
      currentDisplayId: displaySelect.value,
      statusText: statusEl.textContent,
      overlayText: overlayTextEl ? overlayTextEl.textContent : runOverlay.textContent,
      overlayHidden: runOverlay.hidden,
      isMirrorButtonVisible: toggleMirrorBtn ? !toggleMirrorBtn.hidden : false,
      mirrorButtonText: toggleMirrorBtn ? toggleMirrorBtn.textContent : "",
      isOverlayActionVisible: overlayActionBtn ? !overlayActionBtn.hidden : false,
      captureShotsCount: captureShots.length,
      deviceTemplatesCounts: Object.fromEntries(Object.entries(deviceTemplates).map(([k, v]) => [k, v.length])),
      roiEditing,
    }),
    setMode: (mode) => setMode(mode),
    selectDisplay: (id) => {
      displaySelect.value = id;
      displaySelect.dispatchEvent(new Event("change"));
    },
    toggleMirror: (enable) => triggerMirrorToggle(enable),
    addShot: () => addShot(),
    setRoiEditing: (on) => setRoiEditing(on),
    calculateTestRoi: () => calculateTestRoi(),
  };
})();
