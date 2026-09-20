import { createServer } from "node:http";
import { request as httpRequest } from "node:http";
import { createSocket } from "node:dgram";
import { readFileSync, existsSync } from "node:fs";
import { join, extname, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer, WebSocket } from "ws";
import mDNS from "multicast-dns";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MEDIA_DIR = join(__dirname, "..", "media");
const PORT = parseInt(process.env.PORT || "8088", 10);

// ── Server-side config (shared across all tabs) ───────────────────────────────
let serverConfig = {
  mode: process.env.MODE || "mock",        // "mock" | "device"
  target: process.env.TARGET || "192.168.68.110:8787"
};

// ── Mock data ─────────────────────────────────────────────────────────────────
// 1x1 有效的 JPEG 緩衝區（供 Mock 串流模擬影格）
const MOCK_JPEG_FRAME = Buffer.from([
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

const MOCK_MDNS_DEVICES = [
  { name: "MoonClicker-Mock-Device", host: "192.168.0.1", port: 8787 }
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

// ── HTTP server ───────────────────────────────────────────────────────────────
const server = createServer((req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "*");

  if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  const pathname = url.pathname;

  // ── /__dev__ namespace ─────────────────────────────────────────────────────
  if (pathname.startsWith("/__dev__")) {
    handleDevRequest(req, res, pathname, url);
    return;
  }

  // ── /jmuxer.min.js (referenced by index.html) ─────────────────────────────
  if (pathname === "/jmuxer.min.js" || pathname === "/dist/jmuxer.min.js") {
    const p = join(__dirname, "..", "dist", "jmuxer.min.js");
    if (existsSync(p)) {
      res.writeHead(200, { "Content-Type": "application/javascript; charset=utf-8" });
      res.end(readFileSync(p));
      return;
    }
  }

  // ── /* — mock or transparent proxy ────────────────────────────────────────
  if (serverConfig.mode === "mock") {
    handleMockApi(req, res, pathname, url);
  } else {
    proxyTo(serverConfig.target, pathname, url, req, res);
  }
});

// ── /__dev__ handler ──────────────────────────────────────────────────────────
function handleDevRequest(req, res, pathname, url) {
  // GET /__dev__/config
  // POST /__dev__/config { mode, target }
  if (pathname === "/__dev__/config") {
    if (req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(serverConfig));
      return;
    }
    if (req.method === "POST") {
      let body = "";
      req.on("data", c => (body += c));
      req.on("end", () => {
        try {
          const patch = JSON.parse(body);
          if (patch.mode === "mock" || patch.mode === "device") serverConfig.mode = patch.mode;
          if (typeof patch.target === "string" && patch.target) serverConfig.target = patch.target;
        } catch { /* ignore malformed body */ }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(serverConfig));
      });
      return;
    }
  }

  // GET /__dev__/mdns/scan
  if (pathname === "/__dev__/mdns/scan" && req.method === "GET") {
    scanMdns().then(devices => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(devices));
    }).catch(err => {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: err.message }));
    });
    return;
  }

  // Static files under /__dev__
  // /__dev__        → index.html
  if (pathname === "/__dev__") {
    res.writeHead(301, { "Location": "/__dev__/" });
    res.end();
    return;
  }

  // /__dev__/mdns   → mdns.html
  // /__dev__/<file> → media/<file>
  let subPath = pathname.replace(/^\/__dev__/, "") || "/";
  if (subPath === "/" || subPath === "") subPath = "/index.html";
  // bare /mdns → /mdns.html
  if (subPath === "/mdns") subPath = "/mdns.html";

  const filePath = join(MEDIA_DIR, subPath);
  if (existsSync(filePath)) {
    const ext = extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || "application/octet-stream";
    try {
      res.writeHead(200, { "Content-Type": contentType });
      res.end(readFileSync(filePath));
    } catch (e) {
      res.writeHead(500); res.end("Internal Server Error: " + e.message);
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not Found: " + pathname);
}

// ── Mock API (activated when serverConfig.mode === "mock") ────────────────────
function handleMockApi(req, res, pathname, url) {
  if (pathname === "/pair" && req.method === "POST") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", () => {
      let pin = "";
      try { pin = JSON.parse(body).pin || ""; } catch { pin = url.searchParams.get("pin") || ""; }
      if (pin === "123456") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: "mock-token-123456" }));
      } else {
        res.writeHead(401, { "Content-Type": "text/plain" });
        res.end("Invalid PIN");
      }
    });
    return;
  }

  if (pathname === "/displays" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(MOCK_DISPLAYS));
    return;
  }

  if (pathname === "/scripts" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(MOCK_SCRIPTS));
    return;
  }

  const mirrorToggle = pathname.match(/^\/displays\/(\d+)\/mirror$/);
  if (mirrorToggle && req.method === "POST") {
    const id = parseInt(mirrorToggle[1], 10);
    const enable = url.searchParams.get("enable") !== "false";
    const d = MOCK_DISPLAYS.find(x => x.id === id);
    if (d) d.isMirrorActive = enable;
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end(enable ? "Mirror acquired" : "Mirror released");
    return;
  }

  const mirrorStream = pathname.match(/^\/mirror\/(\d+)$/);
  if (mirrorStream && req.method === "GET") {
    const displayId = parseInt(mirrorStream[1], 10);
    const d = MOCK_DISPLAYS.find(x => x.id === displayId);
    if (d && !d.isVirtual && !d.isMirrorActive) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Mirror inactive on physical display");
      return;
    }
    const BOUNDARY = "moonclicker-mirror-frame";
    res.writeHead(200, {
      "Content-Type": `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
      "Cache-Control": "no-cache",
      "Connection": "keep-alive"
    });
    const timer = setInterval(() => {
      if (res.writableEnded || res.destroyed) { clearInterval(timer); return; }
      res.write(`--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${MOCK_JPEG_FRAME.length}\r\n\r\n`);
      res.write(MOCK_JPEG_FRAME);
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

  if (pathname === "/health" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok", mode: "mock" }));
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Unknown mock route: " + pathname);
}

// ── Transparent proxy ─────────────────────────────────────────────────────────
function proxyTo(target, subPath, url, req, res) {
  let host = target;
  let port = 80;
  if (target.includes(":")) {
    const [h, p] = target.split(":");
    host = h;
    port = parseInt(p, 10);
  }

  const fullPath = subPath + (url.search || "");

  const proxyReq = httpRequest(
    { host, port, path: fullPath, method: req.method, headers: { ...req.headers, host: target } },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
      proxyRes.pipe(res);
    }
  );
  proxyReq.on("error", (err) => {
    if (!res.headersSent) {
      res.writeHead(502, { "Content-Type": "text/plain" });
      res.end(`Proxy error → ${target}: ${err.message}`);
    }
  });
  req.pipe(proxyReq);
}

// ── mDNS scan ─────────────────────────────────────────────────────────────────

function scanMdns() {
  if (serverConfig.mode === "mock") {
    return Promise.resolve(MOCK_MDNS_DEVICES);
  }
  return new Promise((resolve) => {
    const mdnsInstance = mDNS();
    const SERVICE = "_moonclicker-workbench._tcp.local";
    const found = [];
    const seen = new Set();

    const timeout = setTimeout(() => {
      try { mdnsInstance.destroy(); } catch { /* ignore */ }
      resolve(found);
    }, 2000);

    mdnsInstance.on("response", (response) => {
      const ptrAnswers = response.answers.filter(a => a.type === "PTR" && a.name === SERVICE);
      if (ptrAnswers.length === 0) return;

      for (const ptr of ptrAnswers) {
        const instanceName = ptr.data;
        const srv = response.additionals.find(a => a.type === "SRV" && a.name === instanceName)
          || response.answers.find(a => a.type === "SRV" && a.name === instanceName);

        if (srv) {
          const target = srv.data.target;
          const aRec = response.additionals.find(a => a.type === "A" && a.name === target)
            || response.answers.find(a => a.type === "A" && a.name === target);
          if (aRec) {
            const ip = aRec.data;
            const port = srv.data.port;
            const name = instanceName.replace("._moonclicker-workbench._tcp.local", "").trim();
            const key = `${ip}:${port}`;
            if (!seen.has(key)) {
              seen.add(key);
              found.push({ name, host: ip, port });
            }
          }
        }
      }
    });

    try {
      mdnsInstance.query({ questions: [{ name: SERVICE, type: "PTR" }] });
    } catch (err) {
      clearTimeout(timeout);
      resolve(found);
    }
  });
}

// ── WebSocket ─────────────────────────────────────────────────────────────────
const wss = new WebSocketServer({ noServer: true });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);

  if (serverConfig.mode === "mock") {
    // Mock WebSocket: periodic log events
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

  // Proxy WS to configured target
  const subPath = url.pathname + (url.search || "");
  const targetWs = new WebSocket(`ws://${serverConfig.target}${subPath}`);
  targetWs.binaryType = "nodebuffer";
  targetWs.on("open", () => {
    ws.on("message", (msg) => targetWs.send(msg));
    targetWs.on("message", (msg) => ws.send(msg));
  });
  targetWs.on("error", (err) => ws.close(1011, "Proxy WS Error: " + err.message));
  targetWs.on("close", () => ws.close());
  ws.on("close", () => targetWs.close());
});

server.on("upgrade", (req, socket, head) => {
  socket.setNoDelay?.(true);
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  // Upgrade all WS except /__dev__ namespace
  if (!url.pathname.startsWith("/__dev__")) {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
  } else {
    socket.destroy();
  }
});

// ── Start ─────────────────────────────────────────────────────────────────────
server.listen(PORT, "0.0.0.0", () => {
  console.log(`\n======================================================`);
  console.log(`🚀 MoonClicker Webview Test Server  (mode: ${serverConfig.mode})`);
  console.log(`   👉 http://127.0.0.1:${PORT}/__dev__          (Mirror Harness)`);
  console.log(`   👉 http://127.0.0.1:${PORT}/__dev__/mdns     (mDNS Discovery)`);
  console.log(`   👉 http://127.0.0.1:${PORT}/__dev__/config   (Server Config API)`);
  console.log(`   target: ${serverConfig.target}`);
  console.log(`======================================================\n`);
});
