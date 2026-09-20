// MoonClicker Webview Mirror Panel Client Script
(function () {
  const vscode = window.vscode || (typeof acquireVsCodeApi === "function" ? acquireVsCodeApi() : null);
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
  const refreshDisplayBtn = document.getElementById("refreshDisplayBtn");
  const logContainer = document.getElementById("logContainer");
  const dataContainer = document.getElementById("dataContainer");
  const autoScrollCb = document.getElementById("autoScroll");
  const clearLogBtn = document.getElementById("clearLogBtn");
  const toggleMirrorBtn = document.getElementById("toggleMirrorBtn");
  const overlayTextEl = document.getElementById("overlayText") || overlayEl;
  const overlayActionBtn = document.getElementById("overlayActionBtn");

  let isCropping = false;
  let stale = false;
  let hasReceivedFrame = false;
  let receivedFramesCount = 0;
  let scriptsList = [];
  let currentDisplays = [];
  let currentDisplayId = null;
  let cropRect = null; // { left, top, right, bottom } in stage pixels
  let dragMode = "None"; // "Create" or DragHandle
  let dragStart = { x: 0, y: 0 };
  let initialRect = null;
  const HANDLE_RADIUS = 8;

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

  if (refreshDisplayBtn) {
    refreshDisplayBtn.addEventListener("click", () => {
      vscode?.postMessage({ type: "refreshDisplays" });
    });
  }

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
    vscode?.postMessage({
      type: "toggleMirror",
      displayId: currentDisplayId,
      enable,
    });
  }

  if (toggleMirrorBtn) {
    toggleMirrorBtn.addEventListener("click", () => {
      const current = currentDisplays.find((d) => d.id === currentDisplayId);
      const targetEnable = current ? !current.isMirrorActive : true;
      triggerMirrorToggle(targetEnable);
    });
  }

  if (overlayActionBtn) {
    overlayActionBtn.addEventListener("click", () => {
      triggerMirrorToggle(true);
    });
  }

  function showOverlay(text, showAction = false) {
    if (overlayTextEl) {
      overlayTextEl.textContent = text;
    } else {
      overlayEl.textContent = text;
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
    overlayEl.hidden = false;
  }
  function hideOverlayIfConnected() {
    if (!stale) {
      overlayEl.hidden = true;
      if (overlayActionBtn) overlayActionBtn.hidden = true;
    }
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

    // 預設一個中央 40% 範圍的裁切框
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
    vscode?.postMessage({ type: "requestScripts" });
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

      vscode?.postMessage({
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
        // Dynamic playback rate catch-up
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
      if (currentVal && scriptsList.some((s) => s.id === currentVal)) {
        scriptSelect.value = currentVal;
      }
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
    if (!state) return;
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
      renderDataTree(dataContainer, e.value || e.data || {});
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

  // 自動化與 LLM DevTools 測試掛載
  window.__MOONCLICKER_TEST__ = {
    getState: () => ({
      hasReceivedFrame,
      receivedFramesCount,
      isCropping,
      stale,
      scriptsCount: scriptsList.length,
      displaysCount: displaySelect.options.length,
      currentDisplayId: displaySelect.value,
      statusText: statusEl.textContent,
      overlayText: overlayTextEl ? overlayTextEl.textContent : overlayEl.textContent,
      overlayHidden: overlayEl.hidden,
      isMirrorButtonVisible: toggleMirrorBtn ? !toggleMirrorBtn.hidden : false,
      mirrorButtonText: toggleMirrorBtn ? toggleMirrorBtn.textContent : "",
      isOverlayActionVisible: overlayActionBtn ? !overlayActionBtn.hidden : false,
    }),
    selectDisplay: (id) => {
      displaySelect.value = id;
      displaySelect.dispatchEvent(new Event("change"));
    },
    toggleMirror: (enable) => triggerMirrorToggle(enable),
    startCropping: () => startCropping(),
    stopCropping: () => stopCropping(),
    calculateRoi: () => calculateRoi(),
    saveCroppedTemplate: () => saveCroppedTemplate(),
  };
})();
