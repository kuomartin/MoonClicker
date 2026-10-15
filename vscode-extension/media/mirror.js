// MoonClicker Webview Mirror Panel Client Script
(function () {
  const vscode = window.vscode || (typeof acquireVsCodeApi === "function" ? acquireVsCodeApi() : null);

  // ---------- 在地化 ----------
  // 原文是英文；擴充套件把 `vscode.l10n.bundle`（英文介面時沒有）放在 window.moonclickerL10n，查不到就顯示原文。
  const l10nBundle = window.moonclickerL10n || {};
  function tr(text, ...args) {
    const template = l10nBundle[text] ?? text;
    return template.replace(/\{(\d+)\}/g, (all, i) => (i in args ? String(args[i]) : all));
  }
  /** 翻譯 HTML 裡的靜態文字：每個文字節點與 title/placeholder 屬性各自查表。 */
  function translateDom(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node.nodeValue.trim();
      if (text && l10nBundle[text]) node.nodeValue = node.nodeValue.replace(text, l10nBundle[text]);
    }
    root.querySelectorAll("[title], [placeholder]").forEach((el) => {
      for (const attr of ["title", "placeholder"]) {
        const value = el.getAttribute(attr);
        if (value && l10nBundle[value]) el.setAttribute(attr, l10nBundle[value]);
      }
    });
  }
  translateDom(document.body);

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

    // #frame 是唯一一支真正接收串流的 <video>，在「運行除錯」、「測試模板」、「採集」之間搬移，
    // 而不是各建立一支——重建會讓 JMuxer 的 MediaSource 附件失效。
    if (mode === "test") {
      testStage.insertBefore(frameEl, testStage.firstChild);
      updateRoiCanvasVisibility();
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
        toggleMirrorBtn.textContent = tr("Stop mirroring");
        toggleMirrorBtn.className = "secondary";
      } else {
        toggleMirrorBtn.textContent = tr("Start mirroring");
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
      toggleMirrorBtn.textContent = enable ? tr("Starting…") : tr("Stopping…");
    }
    if (overlayActionBtn) {
      overlayActionBtn.disabled = true;
      overlayActionBtn.textContent = enable ? tr("Starting…") : tr("Stopping…");
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
      overlayActionBtn.textContent = tr("Start mirroring the physical screen");
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
        statusEl.textContent = tr("Stopped");
        showOverlay(tr("Stream stopped"));
        break;
      case "connecting":
        // 每條連線都是新的 H.264 串流：JMuxer 只採用第一組 SPS，沿用舊的解碼設定會在尺寸不同時解碼失敗。
        jmuxer?.reset();
        statusEl.textContent = tr("Connecting to display {0}…", state.displayId);
        showOverlay(tr("Connecting…"));
        break;
      case "connected":
        statusEl.textContent = tr("Connected to display {0}", state.displayId);
        break;
      case "error":
        statusEl.textContent = tr("Connection failed: {0}", state.message);
        showOverlay(tr("Connection failed: {0}", state.message));
        break;
    }
  }

  // 每支腳本各自的 data（extension 已經把差異套用好，這裡拿到的是完整的 map）。
  const scriptData = {};
  const knownScripts = new Set();
  const scriptFilter = document.getElementById("scriptFilter");

  function noteScript(scriptId) {
    if (!scriptId || knownScripts.has(scriptId)) return;
    knownScripts.add(scriptId);
    const opt = document.createElement("option");
    opt.value = scriptId;
    opt.textContent = scriptId;
    scriptFilter.appendChild(opt);
  }

  function applyScriptFilter() {
    const selected = scriptFilter.value;
    for (const line of logContainer.children) {
      line.hidden = !!selected && !!line.dataset.script && line.dataset.script !== selected;
    }
    renderData();
  }
  scriptFilter.addEventListener("change", applyScriptFilter);

  function handleStreamEvent(e) {
    if (!e) return;
    if (e.case === "log") {
      const { scriptId, line } = e.value || {};
      noteScript(scriptId);
      const div = document.createElement("div");
      div.className = "logLine";
      if (scriptId) {
        div.dataset.script = scriptId;
        const tag = document.createElement("span");
        tag.className = "logScript";
        tag.textContent = "[" + scriptId + "]";
        div.appendChild(tag);
      }
      div.appendChild(document.createTextNode(line || ""));
      div.hidden = !!scriptFilter.value && !!scriptId && scriptId !== scriptFilter.value;
      logContainer.appendChild(div);
      if (autoScrollCb.checked) {
        logContainer.scrollTop = logContainer.scrollHeight;
      }
    } else if (e.case === "data") {
      const { scriptId, data } = e.value || {};
      if (!scriptId) return;
      noteScript(scriptId);
      scriptData[scriptId] = data || {};
      renderData();
      const values = data || {};
      if (currentMode === "test" && testRunning && Object.prototype.hasOwnProperty.call(values, "visionTest")) {
        renderVisionTestResult(values.visionTest);
      }
      if (currentMode === "test" && testRunning && Object.prototype.hasOwnProperty.call(values, "ocrTest")) {
        renderOcrTestResult(values.ocrTest);
      }
    } else if (e.case === "runState") {
      for (const id of (e.value && e.value.runningScriptIds) || []) noteScript(id);
    }
  }

  function renderData() {
    const selected = scriptFilter.value;
    dataContainer.innerHTML = "";
    const ids = selected ? [selected] : Object.keys(scriptData);
    for (const id of ids) {
      if (!selected) {
        const header = document.createElement("div");
        header.className = "tree-group";
        header.textContent = id;
        dataContainer.appendChild(header);
      }
      renderDataTree(dataContainer, id, scriptData[id] || {});
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
    // 裝置端在進入迴圈、跑第一次 vision.find 之前會先送一次 { started: true }（見
    // VisionTestScript.kt）——這是「腳本真的啟動了」的訊號，跟「第一輪比對結果」是兩件
    // 事：沒有這個 special case，v.hit 是 undefined，會被誤判成「未命中」。
    if (v.started) {
      testMatchText.textContent = tr("Detecting…");
      return;
    }
    if (v.hit) {
      const confidence = typeof v.confidence === "number" ? v.confidence.toFixed(2) : "?";
      testMatchText.textContent = tr("Match · confidence {0} (cx={1}, cy={2})", confidence, v.cx, v.cy);
      testMatchIcon.textContent = "✓";
      testMatchIcon.className = "matchIcon hit";
      const hasBox = ["x", "y", "w", "h"].every((k) => typeof v[k] === "number");
      lastHitBox = hasBox ? { x: v.x, y: v.y, w: v.w, h: v.h, confidence: v.confidence } : null;
    } else {
      testMatchText.textContent = tr("No match");
      testMatchIcon.textContent = "✕";
      testMatchIcon.className = "matchIcon miss";
      lastHitBox = null;
    }
    if (currentMode === "test" && !testRoiCanvas.hidden) drawRoiOverlay();
  }

  // 裝置端 `data.set("ocrTest", ...)` 回報的結果（見 OcrTestScript.kt）。跟 visionTest 一樣是
  // JSON 字串；空陣列在 Lua 端會被序列化成 {}，所以 lines 不是陣列時當成沒有字。
  function renderOcrTestResult(v) {
    if (typeof v === "string") {
      try {
        v = JSON.parse(v);
      } catch {
        return;
      }
    }
    if (!v || typeof v !== "object") return;
    if (v.started) {
      testMatchText.textContent = tr("Recognizing… (OCR loads on first use)");
      return;
    }
    const now = Date.now();
    if (lastOcrResultAt) testLatency.textContent = tr("Updated every {0} ms", now - lastOcrResultAt);
    lastOcrResultAt = now;

    const isBox = (b) => b && ["x", "y", "w", "h"].every((k) => typeof b[k] === "number");
    if (testTarget() === "find") {
      lastOcrLines = [];
      if (v.hit) {
        const similarity = typeof v.confidence === "number" ? v.confidence.toFixed(2) : "?";
        testMatchText.textContent = tr("Match · similarity {0}\nLine: {1}", similarity, v.line || "");
        testMatchIcon.textContent = "✓";
        testMatchIcon.className = "matchIcon hit";
        lastHitBox = isBox(v) ? { x: v.x, y: v.y, w: v.w, h: v.h, confidence: v.confidence } : null;
      } else {
        testMatchText.textContent = tr("No match");
        testMatchIcon.textContent = "✕";
        testMatchIcon.className = "matchIcon miss";
        lastHitBox = null;
      }
    } else {
      lastHitBox = null;
      lastOcrLines = Array.isArray(v.lines) ? v.lines.filter(isBox) : [];
      if (lastOcrLines.length === 0) {
        testMatchText.textContent = tr("No text recognized");
        testMatchIcon.textContent = "✕";
        testMatchIcon.className = "matchIcon miss";
      } else {
        testMatchText.textContent = lastOcrLines
          .map((l) => l.text + " (" + (typeof l.confidence === "number" ? l.confidence.toFixed(2) : "?") + ")")
          .join("\n");
        testMatchIcon.textContent = "✓";
        testMatchIcon.className = "matchIcon hit";
      }
    }
    if (currentMode === "test" && !testRoiCanvas.hidden) drawRoiOverlay();
  }

  // 上次畫出來的值，以「腳本 id + key」為鍵，變動的值閃一下。
  const lastData = {};
  function renderDataTree(container, scriptId, data) {
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
      const flashKey = scriptId + "\u0000" + k;
      if (lastData[flashKey] !== v) valSpan.classList.add("flash");
      lastData[flashKey] = v;
      node.appendChild(keySpan);
      node.appendChild(valSpan);
      container.appendChild(node);
    }
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
        showOverlay(tr("The stream has stalled. The device may be asleep, off Wi-Fi, or the workbench service has stopped."));
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
          label += " " + (d.isMirrorActive ? tr("[physical · mirroring]") : tr("[physical · off]"));
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
        testLatency.textContent = tr("Polling every {0} ms", currentIntervalMs());
        testMatchText.textContent = tr("Waiting for results…");
        lastOcrResultAt = 0;
        updateRoiCanvasVisibility();
      } else {
        testRunning = false;
        setTestStatusPill("error", msg.error || tr("Failed to start the test"));
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
        lastHitBox = null;
        setTestStatusPill("idle");
        resetTestMatchUi();
        updateRoiCanvasVisibility();
      } else {
        pendingRestart = false;
        setTestStatusPill("error", msg.error || tr("Failed to stop"));
      }
      updateTestStartStopBtn();
    } else if (msg.type === "saveTemplateResult") {
      buildSaveBtn.disabled = false;
      if (msg.success && pendingSave) {
        buildStatus.style.color = "var(--moon-success)";
        buildStatus.textContent = tr("Saved to the device");
        buildTemplateName.value = "";
        requestTemplatesFor(pendingSave.scriptId); // 重新拉裝置端的真實清單，而不是本地猜一筆
        setTimeout(() => { buildStatus.textContent = ""; }, 2000);
      } else {
        buildStatus.style.color = "var(--vscode-errorForeground)";
        buildStatus.textContent = msg.error || tr("Save failed");
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
        buildStatus.textContent = msg.error || tr("Delete failed");
      }
    } else if (msg.type === "createScriptResult") {
      addingScript = false;
      addScriptBtn.disabled = false;
      if (msg.success) {
        setWriteStatus(tr("Created"), false);
        newScriptIdInput.value = "";
        setTimeout(() => { writeStatus.textContent = ""; }, 2000);
        // 新腳本清單本身由 mirrorPanel.ts 緊接著送一次 "scripts" 補上，這裡不用自己拼。
      } else {
        setWriteStatus(msg.error || tr("Create failed"), true);
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
    captureQueueCount.textContent = tr("Capture queue · {0}", captureShots.length);
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
  // ROI 編輯同一套幾何算法）；「儲存模板」「刪除模板」「列出模板」都真的呼叫裝置既有、
  // 新增的 PUT / GET / DELETE /scripts/{id}/templates(/{name})。deviceTemplates 是
  // 裝置端清單的本地快取（key 是 scriptId），每次存檔、刪除成功都會重新拉一次，
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
        img.onerror = () => reject(new Error(tr("Failed to decode the image")));
        img.src = shot.dataUrl;
      });
    }
    return shotImageCache[shot.id];
  }

  function renderBuildShotPicker() {
    buildShotPicker.innerHTML = "";
    if (captureShots.length === 0) {
      buildShotPicker.innerHTML = '<span class="emptyHint"></span>';
      buildShotPicker.firstChild.textContent = tr("No captures yet. Take one in \"1 · Capture\" first.");
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
      opt.textContent = tr("(Not connected, cannot list scripts)");
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
    return s ? (s.name || s.id) : buildScriptSelect.value || tr("this script");
  }

  function renderBuildTemplateList() {
    const scriptId = buildScriptSelect.value;
    buildTemplateListLabel.textContent = tr("Templates in {0}", currentBuildScriptName());
    buildTemplateList.innerHTML = "";
    const list = deviceTemplates[scriptId];
    if (list === undefined) {
      buildTemplateList.innerHTML = '<span class="emptyHint"></span>';
      buildTemplateList.firstChild.textContent = deviceTemplatesLoading[scriptId] ? tr("Loading…") : tr("Not connected");
      return;
    }
    if (list.length === 0) {
      buildTemplateList.innerHTML = '<span class="emptyHint"></span>';
      buildTemplateList.firstChild.textContent = tr("No templates yet");
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
        '<button type="button" class="linkBtn">✕</button>';
      row.querySelector("button").title = tr("Delete template");
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
      buildStatus.textContent = tr("Choose a script first");
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    if (!name) {
      buildStatus.textContent = tr("Enter a template name");
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    if (!shot) {
      buildStatus.textContent = tr("Take a capture in \"1 · Capture\" first");
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }
    const roi = calculateBuildRoi();
    if (!roi) {
      buildStatus.textContent = tr("Drag out a valid crop area first");
      buildStatus.style.color = "var(--vscode-errorForeground)";
      return;
    }

    buildSaveBtn.disabled = true;
    buildStatus.style.color = "var(--vscode-foreground)";
    buildStatus.textContent = tr("Cropping…");
    pendingSave = { scriptId, name, threshold: Number(buildThreshold.value) / 100 };

    try {
      const img = await getShotImage(shot);
      const helperCanvas = document.createElement("canvas");
      helperCanvas.width = roi.w;
      helperCanvas.height = roi.h;
      helperCanvas.getContext("2d").drawImage(img, roi.x, roi.y, roi.w, roi.h, 0, 0, roi.w, roi.h);
      const dataUrl = helperCanvas.toDataURL("image/png");
      const pngBase64 = dataUrl.split(",")[1];
      buildStatus.textContent = tr("Saving…");
      vscode?.postMessage({ type: "saveTemplate", scriptId, templateName: name, roi, pngBase64 });
    } catch (err) {
      pendingSave = null;
      buildSaveBtn.disabled = false;
      buildStatus.style.color = "var(--vscode-errorForeground)";
      buildStatus.textContent = tr("Failed to crop: {0}", err && err.message ? err.message : String(err));
    }
  });

  // ==================================================================
  // 3 · 測試模板 —— ROI 拖曳、開始/停止、比對結果都是真的（裝置端 vision-test 端點）；
  // 模板來源是 deviceTemplates（跟「2 · 建立模板」共用，見該區塊開頭的說明），選了
  // 哪個腳本就拉那個腳本的清單
  // ==================================================================
  const testTargetSelect = document.getElementById("testTargetSelect");
  const testTemplateControls = document.getElementById("testTemplateControls");
  const testTextControls = document.getElementById("testTextControls");
  const testTextInput = document.getElementById("testTextInput");
  const testExactCheckbox = document.getElementById("testExactCheckbox");
  const testThresholdControls = document.getElementById("testThresholdControls");
  const testScriptSelect = document.getElementById("testScriptSelect");
  const testTemplateSelect = document.getElementById("testTemplateSelect");
  const testThreshold = document.getElementById("testThreshold");
  const testThresholdVal = document.getElementById("testThresholdVal");
  const testIntervalInput = document.getElementById("testIntervalInput");
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

  const VISION_TEST_DEBOUNCE_MS = 500;
  let testRunning = false;
  let testPending = false; // start/stop RPC 進行中
  let testRestartTimer = null;
  let lastHitBox = null; // 裝置回報的命中區域（邏輯座標 {x,y,w,h,confidence}），畫在 testRoiCanvas 上
  let lastOcrLines = []; // vision.read/read_lines 回報的每一行（邏輯座標 {text,confidence,x,y,w,h}）
  let lastOcrResultAt = 0; // 上一次收到 OCR 結果的時間，用來顯示更新間隔
  // OCR 每輪本身要等辨識完成，間隔是辨識完之後再睡的時間，預設比模板長。
  const DEFAULT_INTERVAL_MS = { template: 300, ocr: 500 };

  /** "template" 或 OCR 模式（"read"/"read_lines"/"find"，對應裝置端 /ocr-test 的 mode）。 */
  function testTarget() {
    return testTargetSelect.value;
  }

  function isOcrTarget() {
    return testTarget() !== "template";
  }

  function currentIntervalMs() {
    const v = parseInt(testIntervalInput.value, 10);
    return Number.isFinite(v) && v > 0 ? v : DEFAULT_INTERVAL_MS[isOcrTarget() ? "ocr" : "template"];
  }

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
        ? tr("(Loading…)")
        : tr("(No templates in this script yet. Create one in \"2 · Build templates\".)");
      testTemplateSelect.appendChild(opt);
      testThreshold.disabled = true;
    } else {
      testThreshold.disabled = false;
      for (const t of list) {
        const opt = document.createElement("option");
        opt.value = t.name;
        opt.textContent = t.name + " (" + t.roi.w + " × " + t.roi.h + ")";
        testTemplateSelect.appendChild(opt);
      }
      if (prev && list.some((t) => t.name === prev)) {
        testTemplateSelect.value = prev;
      }
    }
    resetTestMatchUi();
    updateSnippet();
  }

  testTargetSelect.addEventListener("change", () => {
    const ocr = isOcrTarget();
    testTemplateControls.hidden = ocr;
    testTextControls.hidden = testTarget() !== "find";
    // 讀取沒有「要找的東西」，門檻只對模板與找文字有意義。
    testThresholdControls.hidden = testTarget() === "read" || testTarget() === "read_lines";
    // 間隔還是另一種目標的預設值時跟著換；使用者改過就不動。
    const other = DEFAULT_INTERVAL_MS[ocr ? "template" : "ocr"];
    if (parseInt(testIntervalInput.value, 10) === other) {
      testIntervalInput.value = String(DEFAULT_INTERVAL_MS[ocr ? "ocr" : "template"]);
    }
    resetTestMatchUi();
    updateSnippet();
    scheduleTestRestartIfRunning();
  });

  testTextInput.addEventListener("input", () => {
    updateSnippet();
    scheduleTestRestartIfRunning();
  });

  testExactCheckbox.addEventListener("change", () => {
    updateSnippet();
    scheduleTestRestartIfRunning();
  });

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

  testIntervalInput.addEventListener("change", () => {
    if (testRunning) {
      testLatency.textContent = tr("Polling every {0} ms", currentIntervalMs());
    }
    scheduleTestRestartIfRunning();
  });

  function resetTestMatchUi() {
    testMatchIcon.textContent = "?";
    testMatchIcon.className = "matchIcon";
    lastHitBox = null;
    lastOcrLines = [];
    lastOcrResultAt = 0;
    if (currentMode === "test" && !testRoiCanvas.hidden) drawRoiOverlay();
    if (isOcrTarget()) {
      testMatchText.textContent = testRunning ? tr("Waiting for results…") : tr("Test not started");
      return;
    }
    const t = currentTestTemplate();
    if (!t) {
      testMatchText.textContent = tr("No template to match");
      return;
    }
    testMatchText.textContent = testRunning ? tr("Waiting for results…") : tr("Test not started");
  }

  function currentTestTemplate() {
    return currentScriptTemplates().find((t) => t.name === testTemplateSelect.value) || null;
  }

  function currentTestTemplateName() {
    const t = currentTestTemplate();
    return t ? t.name : "template";
  }

  // 對應真正的 Lua API（見 docs/lua-api.md 的 `vision` / `input`），不是隨便編的介面。
  function luaRoi(roi) {
    return "{ x = " + roi.x + ", y = " + roi.y + ", w = " + roi.w + ", h = " + roi.h + " }";
  }

  function luaString(text) {
    return JSON.stringify(text); // Lua 的雙引號字串跳脫規則對一般文字與 JSON 相容
  }

  function ocrSnippet() {
    const roi = calculateTestRoi();
    const threshold = (testThreshold.value / 100).toFixed(2);
    switch (testTarget()) {
      case "read":
        return (
          "local line = vision.read(" + luaRoi(roi || { x: 96, y: 420, w: 130, h: 40 }) + ")\n" +
          "if line then\n" +
          "  log(line.text, line.confidence)\n" +
          "end"
        );
      case "read_lines":
        return (
          "for _, line in ipairs(vision.read_lines(" + (roi ? luaRoi(roi) : "") + ")) do\n" +
          "  log(line.text, line.confidence)\n" +
          "end"
        );
      default:
        return (
          "local hit = vision.wait({\n" +
          "  text = " + luaString(testTextInput.value || tr("Start")) + ",\n" +
          (testExactCheckbox.checked ? "  exact = true,\n" : "") +
          (roi ? "  roi = " + luaRoi(roi) + ",\n" : "") +
          "  threshold = " + threshold + ",\n" +
          "}, 10000)\n" +
          "if hit then\n" +
          "  input.tap(hit.cx, hit.cy)\n" +
          "end"
        );
    }
  }

  function updateSnippet() {
    if (isOcrTarget()) {
      testSnippet.textContent = ocrSnippet();
      return;
    }
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
    copySnippetBtn.textContent = tr("Copied!");
    setTimeout(() => { copySnippetBtn.textContent = original; }, 1200);
  });

  // ---------- 即時測試比對 start/stop（真實 RPC，共用 /run 執行槽） ----------

  const TEST_STATUS_LABELS = {
    idle: "Not started",
    starting: "Starting…",
    running: "Matching live",
    stopping: "Stopping…",
    error: "Error",
  };

  function setTestStatusPill(state, detail) {
    testStatusPill.classList.toggle("statusPill-error", state === "error");
    testStatusPill.innerHTML = '<span class="statusDot"></span>';
    testStatusPill.append(tr(TEST_STATUS_LABELS[state]));
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
      testStartStopBtn.textContent = testRunning ? tr("Stopping…") : tr("Starting…");
    } else {
      testStartStopBtn.textContent = testRunning ? tr("Stop test") : tr("Start test");
    }
  }

  function requestStartOcrTest() {
    if (currentDisplayId === null) {
      setTestStatusPill("error", tr("No display selected"));
      return;
    }
    const mode = testTarget();
    const roi = calculateTestRoi();
    if (mode === "read" && !roi) {
      setTestStatusPill("error", tr("Reading one line needs an ROI. Use \"Adjust ROI\" to box the line first."));
      return;
    }
    if (mode === "find" && !testTextInput.value) {
      setTestStatusPill("error", tr("Enter the text to find"));
      return;
    }
    testPending = true;
    updateTestStartStopBtn();
    setTestStatusPill("starting");
    vscode?.postMessage({
      type: "startOcrTest",
      displayId: currentDisplayId,
      mode,
      roi,
      text: testTextInput.value,
      exact: testExactCheckbox.checked,
      threshold: testThreshold.value / 100,
      intervalMs: currentIntervalMs(),
    });
  }

  function requestStartVisionTest() {
    if (isOcrTarget()) {
      requestStartOcrTest();
      return;
    }
    const t = currentTestTemplate();
    if (!t) {
      setTestStatusPill("error", tr("No template to test"));
      return;
    }
    if (currentDisplayId === null) {
      setTestStatusPill("error", tr("No display selected"));
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
      intervalMs: currentIntervalMs(),
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

  // ROI 拖曳、閾值調整時，若測試正在跑，Lua 迴圈無法動態改參數——debounce 後
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
      updateSnippet();
    }
    updateRoiCanvasVisibility();
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

  // 邏輯座標（裝置回報，跟 ROI 同一個座標系，見 ADR-0013）轉成目前 canvas 的像素座標，
  // 跟 calculateTestRoi() 反方向的換算。
  function logicalToCanvasRect(r) {
    const fit = roiAspectFit();
    if (fit.scale <= 0) return null;
    return {
      left: fit.left + r.x * fit.scale,
      top: fit.top + r.y * fit.scale,
      right: fit.left + (r.x + r.w) * fit.scale,
      bottom: fit.top + (r.y + r.h) * fit.scale,
    };
  }

  function drawRoiOverlay() {
    testCtx.clearRect(0, 0, testRoiCanvas.width, testRoiCanvas.height);
    if (roiRect) {
      const r = roiNorm(roiRect);
      testCtx.strokeStyle = "#4fc1ff";
      testCtx.lineWidth = 2;
      testCtx.setLineDash([6, 4]);
      testCtx.strokeRect(r.left, r.top, r.right - r.left, r.bottom - r.top);
      testCtx.setLineDash([]);
      // 拖曳手把只在真的可以拖的時候畫，不然「調整 ROI」沒開時看起來像能拖但拖不動。
      if (roiEditing) {
        testCtx.fillStyle = "#ffffff";
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
    }
    for (const line of lastOcrLines) {
      const lr = logicalToCanvasRect(line);
      if (!lr) continue;
      testCtx.strokeStyle = "#d29922";
      testCtx.lineWidth = 2;
      testCtx.strokeRect(lr.left, lr.top, lr.right - lr.left, lr.bottom - lr.top);
      const label = line.text + " " + (typeof line.confidence === "number" ? line.confidence.toFixed(2) : "");
      testCtx.font = "11px -apple-system, BlinkMacSystemFont, sans-serif";
      const labelW = testCtx.measureText(label).width + 10;
      const labelH = 16;
      // 貼左上角，框太靠近 canvas 頂端時往框內塞。
      const ly = lr.top - labelH - 2 >= 0 ? lr.top - labelH - 2 : lr.top + 2;
      testCtx.fillStyle = "#1e1e1e";
      testCtx.fillRect(lr.left, ly, labelW, labelH);
      testCtx.fillStyle = "#d29922";
      testCtx.textBaseline = "middle";
      testCtx.fillText(label, lr.left + 5, ly + labelH / 2 + 1);
    }
    if (lastHitBox) {
      const hr = logicalToCanvasRect(lastHitBox);
      if (hr) {
        testCtx.strokeStyle = "#3fb950";
        testCtx.lineWidth = 2;
        testCtx.strokeRect(hr.left, hr.top, hr.right - hr.left, hr.bottom - hr.top);
        if (typeof lastHitBox.confidence === "number") {
          const label = lastHitBox.confidence.toFixed(2);
          testCtx.font = "11px -apple-system, BlinkMacSystemFont, sans-serif";
          const padX = 5;
          const labelH = 16;
          const labelW = testCtx.measureText(label).width + padX * 2;
          // 貼右上角，框太靠近 canvas 頂端時往框內塞，不要被裁掉看不到。
          const lx = hr.right - labelW;
          const ly = hr.top - labelH - 2 >= 0 ? hr.top - labelH - 2 : hr.top + 2;
          testCtx.fillStyle = "#1e1e1e";
          testCtx.fillRect(lx, ly, labelW, labelH);
          testCtx.strokeRect(lx, ly, labelW, labelH);
          testCtx.fillStyle = "#3fb950";
          testCtx.textBaseline = "middle";
          testCtx.fillText(label, lx + padX, ly + labelH / 2 + 1);
        }
      }
    }
  }

  // ROI 框跟命中框要看得到，不是只有「調整 ROI」開著才顯示——測試進行中即使沒在拖曳
  // ROI，使用者也該看到目前對著哪個區域、命中在哪裡。
  function updateRoiCanvasVisibility() {
    testRoiCanvas.hidden = !(roiEditing || testRunning);
    if (!testRoiCanvas.hidden) {
      resizeRoiCanvas();
      drawRoiOverlay();
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
  // 運行除錯畫面上直接操作：點擊轉 tap、拖曳轉 swipe。座標以畫面比例送出，由 App 換算到
  // 顯示器目前的邏輯空間。手勢放開才送，按住多久、拖曳多久就照樣重播多久。
  // ==================================================================
  const TAP_SLOP_PX = 6;
  const MIN_GESTURE_MS = 50;
  const MAX_GESTURE_MS = 10000;
  const MAX_GESTURE_POINTS = 64;
  let gesture = null;

  /** 視窗座標 → 影格內的比例座標（object-fit: contain 的黑邊不算）。沒有影格時回 null。 */
  function framePoint(e) {
    const rect = frameEl.getBoundingClientRect();
    const vw = frameEl.videoWidth;
    const vh = frameEl.videoHeight;
    if (!vw || !vh || rect.width <= 0 || rect.height <= 0) return null;
    const scale = Math.min(rect.width / vw, rect.height / vh);
    const w = vw * scale;
    const h = vh * scale;
    const left = rect.left + (rect.width - w) / 2;
    const top = rect.top + (rect.height - h) / 2;
    return { x: (e.clientX - left) / w, y: (e.clientY - top) / h, clientX: e.clientX, clientY: e.clientY };
  }

  /** 均勻抽樣到最多 [max] 點，保留頭尾。 */
  function downsample(points, max) {
    if (points.length <= max) return points;
    const out = [];
    for (let i = 0; i < max; i++) out.push(points[Math.round((i * (points.length - 1)) / (max - 1))]);
    return out;
  }

  const clamp01 = (v) => Math.min(1, Math.max(0, v));

  runStage.addEventListener("pointerdown", (e) => {
    // 只接畫面本身：overlay 上的按鈕照常可以按。
    if (e.target !== runStage || e.button !== 0 || !hasReceivedFrame || currentDisplayId === null) return;
    const p = framePoint(e);
    if (!p || p.x < 0 || p.x > 1 || p.y < 0 || p.y > 1) return;
    runStage.setPointerCapture(e.pointerId);
    gesture = { start: performance.now(), points: [p], moved: false };
    e.preventDefault();
  });

  runStage.addEventListener("pointermove", (e) => {
    if (!gesture) return;
    const p = framePoint(e);
    if (!p) return;
    const first = gesture.points[0];
    if (Math.hypot(p.clientX - first.clientX, p.clientY - first.clientY) > TAP_SLOP_PX) gesture.moved = true;
    gesture.points.push(p);
  });

  runStage.addEventListener("pointerup", () => {
    if (!gesture) return;
    const { start, points, moved } = gesture;
    gesture = null;
    const durationMs = Math.round(Math.min(MAX_GESTURE_MS, Math.max(MIN_GESTURE_MS, performance.now() - start)));
    const path = moved ? downsample(points, MAX_GESTURE_POINTS) : [points[0]];
    vscode?.postMessage({
      type: "inject",
      displayId: currentDisplayId,
      points: path.map((p) => [clamp01(p.x), clamp01(p.y)]),
      durationMs,
    });
  });

  runStage.addEventListener("pointercancel", () => {
    gesture = null;
  });

  // ==================================================================
  // 4 · 編寫 —— 全部是真的：清單（含裝置回報的模板數、上次修改）、「在編輯器開啟」
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
    return new Date(ms).toLocaleString(document.documentElement.lang || undefined, { hour12: false });
  }

  function renderWriteScriptCards() {
    writeScriptCards.innerHTML = "";
    if (scriptsList.length === 0) {
      writeScriptCards.innerHTML = '<span class="emptyHint"></span>';
      writeScriptCards.firstChild.textContent = tr("Not connected, cannot list scripts");
      return;
    }
    for (const s of scriptsList) {
      const templateCount = typeof s.templateCount === "number" ? s.templateCount : (deviceTemplates[s.id] || []).length;
      const card = document.createElement("div");
      card.className = "scriptCard";
      card.innerHTML =
        '<svg class="scriptCardIcon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"></polyline><polyline points="8 6 2 12 8 18"></polyline></svg>' +
        '<div class="scriptCardInfo"><span class="scriptCardName"></span><span class="scriptCardMeta"></span></div>' +
        '<button type="button" class="secondary"></button>';
      card.querySelector("button").textContent = tr("Open in editor");
      card.querySelector(".scriptCardName").textContent = s.name || s.id;
      card.querySelector(".scriptCardMeta").textContent = tr("{0} template(s) · modified {1}", templateCount, formatModified(s.modifiedMs));
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
      setWriteStatus(tr("Enter a script name"), true);
      return;
    }
    if (id.startsWith(".") || !SCRIPT_ID_PATTERN.test(id)) {
      setWriteStatus(tr('Only lowercase letters, digits, ".", "_" and "-" are allowed, and the name cannot start with "."'), true);
      return;
    }
    addingScript = true;
    addScriptBtn.disabled = true;
    writeStatus.style.color = "var(--vscode-foreground)";
    writeStatus.textContent = tr("Creating…");
    vscode?.postMessage({ type: "createScript", scriptId: id });
  });

  // ---------- 初始渲染 ----------
  renderCaptureQueue();
  populateScriptSelects();
  renderTestTemplateOptions();
  testLatency.textContent = tr("Polling every {0} ms", currentIntervalMs());

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
