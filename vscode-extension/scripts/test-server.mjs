import { createServer } from "node:http";
import { request as httpRequest } from "node:http";
import { readFileSync, existsSync } from "node:fs";
import { join, extname, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, WebSocket } from "ws";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MEDIA_DIR = join(__dirname, "..", "media");
let PORT = parseInt(process.env.PORT || "8088", 10);

// 1x1 有效的 JPEG 緩衝區（紅色與白色，供 Mock 串流切換模擬影格變化）
const MOCK_JPEG_FRAME_A = Buffer.from([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
  0x00, 0x48, 0x00, 0x00, 0xff, 0xdb, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08,
  0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0a, 0x0c, 0x14, 0x0d, 0x0c, 0x0b, 0x0b, 0x0c, 0x19, 0x12,
  0x13, 0x0f, 0x14, 0x1d, 0x1a, 0x1f, 0x1e, 0x1d, 0x1a, 0x1c, 0x1c, 0x20, 0x24, 0x2e, 0x27, 0x20,
  0x22, 0x2c, 0x23, 0x1c, 0x1c, 0x28, 0x37, 0x29, 0x2c, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1f, 0x27,
  0x39, 0x3d, 0x38, 0x32, 0x3c, 0x2e, 0x33, 0x34, 0x32, 0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01,
  0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xff, 0xc4, 0x00, 0x1f, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01,
  0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04,
  0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f,
  0x00, 0xbf, 0x80, 0xff, 0xd9
]);

const MOCK_DISPLAYS = [
  { id: 0, name: "Physical Display", width: 1080, height: 2400, isVirtual: false, isMirrorActive: true },
  { id: 8, name: "Virtual Display 8", width: 2400, height: 1080, isVirtual: true, isMirrorActive: true }
];

const MOCK_SCRIPTS = [
  { id: "demo-game", name: "Demo Game" },
  { id: "test-alto", name: "Alto's Adventure" }
];

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg"
};

const server = createServer(async (req, res) => {
  // CORS 標頭
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "*");

  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  const pathname = url.pathname;

  // 1. Mock API 處理
  if (pathname.startsWith("/mock/")) {
    handleMockRequest(req, res, pathname, url);
    return;
  }

  // 2. Proxy 轉發至真機 Workbench 伺服器
  if (pathname.startsWith("/proxy")) {
    handleProxyRequest(req, res, pathname, url);
    return;
  }

  // 3. jmuxer 支援
  if (pathname === "/jmuxer.min.js" || pathname === "/dist/jmuxer.min.js") {
    const jmuxerPath = join(__dirname, "..", "dist", "jmuxer.min.js");
    if (existsSync(jmuxerPath)) {
      res.writeHead(200, { "Content-Type": "application/javascript; charset=utf-8" });
      res.end(readFileSync(jmuxerPath));
      return;
    }
  }

  // 4. 靜態檔案託管 (media/)
  let filePath = join(MEDIA_DIR, pathname === "/" ? "index.html" : pathname);
  if (!existsSync(filePath) && existsSync(filePath + ".html")) {
    filePath += ".html";
  }

  if (existsSync(filePath)) {
    const ext = extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || "application/octet-stream";
    try {
      const content = readFileSync(filePath);
      res.writeHead(200, { "Content-Type": contentType });
      res.end(content);
      return;
    } catch (e) {
      res.writeHead(500);
      res.end("Internal Server Error: " + e.message);
      return;
    }
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not Found: " + pathname);
});

// 處理 Mock API
function handleMockRequest(req, res, pathname, url) {
  if (pathname === "/mock/displays" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(MOCK_DISPLAYS));
    return;
  }
  if (pathname === "/mock/scripts" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(MOCK_SCRIPTS));
    return;
  }
  const mirrorMatch = pathname.match(/^\/mock\/displays\/(\d+)\/mirror$/);
  if (mirrorMatch && req.method === "POST") {
    const id = parseInt(mirrorMatch[1], 10);
    const enable = url.searchParams.get("enable") !== "false";
    const d = MOCK_DISPLAYS.find(x => x.id === id);
    if (d) d.isMirrorActive = enable;
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end(enable ? "Mirror acquired" : "Mirror released");
    return;
  }
  const streamMatch = pathname.match(/^\/mock\/mirror\/(\d+)$/);
  if (streamMatch && req.method === "GET") {
    const displayId = parseInt(streamMatch[1], 10);
    const d = MOCK_DISPLAYS.find(x => x.id === displayId);
    if (d && !d.isVirtual && !d.isMirrorActive) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Mirror inactive on physical display");
      return;
    }

    const BOUNDARY = "relc-mirror-frame";
    res.writeHead(200, {
      "Content-Type": `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
      "Cache-Control": "no-cache",
      "Connection": "keep-alive"
    });

    const timer = setInterval(() => {
      if (res.writableEnded || res.destroyed) {
        clearInterval(timer);
        return;
      }
      const frame = MOCK_JPEG_FRAME_A;
      res.write(`--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`);
      res.write(frame);
      res.write("\r\n");
    }, 100);

    req.on("close", () => clearInterval(timer));
    return;
  }
  if (pathname.includes("/templates/") && req.method === "PUT") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("OK");
    return;
  }

  res.writeHead(404);
  res.end("Unknown mock route");
}

// 處理 Proxy 轉發
function handleProxyRequest(req, res, pathname, url) {
  let target = url.searchParams.get("target");
  let subPath = "";

  if (target) {
    subPath = url.searchParams.get("path") || pathname.replace(/^\/proxy/, "") || "/";
  } else {
    // 支援 /proxy/192.168.68.110:8787/displays 形式
    const match = pathname.match(/^\/proxy\/([^\/]+)(.*)$/);
    if (match) {
      target = decodeURIComponent(match[1]);
      subPath = match[2] || "/";
    }
  }

  if (!target) {
    res.writeHead(400, { "Content-Type": "text/plain" });
    res.end("Missing target in proxy request (e.g. /proxy?target=ip:port or /proxy/ip:port/...)");
    return;
  }

  let host = target;
  let port = 80;
  if (target.includes(":")) {
    const [h, p] = target.split(":");
    host = h;
    port = parseInt(p, 10);
  }

  // 保留 subPath 的 query string（除了 target 參數）
  const forwardQuery = new URLSearchParams(url.searchParams);
  forwardQuery.delete("target");
  forwardQuery.delete("path");
  const forwardQueryStr = forwardQuery.toString();
  const fullPath = subPath + (forwardQueryStr ? `?${forwardQueryStr}` : "");

  const proxyReq = httpRequest({
    host,
    port,
    path: fullPath,
    method: req.method,
    headers: {
      ...req.headers,
      host: target
    }
  }, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
    proxyRes.pipe(res);
  });

  proxyReq.on("error", (err) => {
    if (!res.headersSent) {
      res.writeHead(502, { "Content-Type": "text/plain" });
      res.end(`Proxy error connecting to ${target}: ${err.message}`);
    }
  });

  req.pipe(proxyReq);
}

// 4. WebSocket 升級支援 (Proxy WS & Mock WS)
const wss = new WebSocketServer({ noServer: true });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  if (url.pathname.startsWith("/mock")) {
    // Mock WebSocket: 定期送 log 與 data
    ws.send(JSON.stringify({ case: "log", value: "[Mock] Script system ready." }));
    ws.send(JSON.stringify({ case: "data", value: { status: "ready", fps: 60 } }));
    const logTimer = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ case: "log", value: `[Mock log] Tick at ${new Date().toLocaleTimeString()}` }));
      }
    }, 3000);
    ws.on("close", () => clearInterval(logTimer));
    return;
  }

  // Proxy WebSocket 到真機
  let target = url.searchParams.get("target");
  if (!target) {
    const match = url.pathname.match(/^\/proxy-ws\/([^\/]+)/);
    if (match) target = decodeURIComponent(match[1]);
  }
  if (!target) {
    ws.close(1002, "Missing target");
    return;
  }

  const targetPath = url.searchParams.get("path") || "/";
  const targetWs = new WebSocket(`ws://${target}${targetPath}`);
  targetWs.binaryType = "nodebuffer";
  targetWs.on("open", () => {
    ws.on("message", (msg) => targetWs.send(msg));
    targetWs.on("message", (msg) => ws.send(msg));
  });
  targetWs.on("error", (err) => {
    ws.close(1011, "Proxy WS Error: " + err.message);
  });
  targetWs.on("close", () => ws.close());
  ws.on("close", () => targetWs.close());
});

server.on("upgrade", (req, socket, head) => {
  socket.setNoDelay?.(true);
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  if (url.pathname.startsWith("/proxy-ws") || url.pathname.startsWith("/mock/ws")) {
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req);
    });
  } else {
    socket.destroy();
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`\n======================================================`);
  console.log(`🚀 ReLC Webview Test Server running at:`);
  console.log(`   👉 http://127.0.0.1:${PORT}/`);
  console.log(`   👉 http://127.0.0.1:${PORT}/?mode=mock (Mock 模式)`);
  console.log(`   👉 http://127.0.0.1:${PORT}/?target=192.168.68.110:8787 (真機模式)`);
  console.log(`======================================================\n`);
});
