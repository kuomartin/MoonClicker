import assert from "node:assert/strict";
import { createServer, type Server, type ServerResponse } from "node:http";
import { after, before, test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { MirrorConnection, MultipartFrameParser, type MirrorConnectionState } from "../mirrorConnection";

const BOUNDARY = "relc-mirror-frame";

function frameBytes(content: string): Buffer {
  return Buffer.from(content);
}

/** 比照 WorkbenchServer.kt `/mirror/{displayId}` route 寫出的確切 multipart 格式編一幀。 */
function encodePart(body: Buffer): Buffer {
  return Buffer.concat([
    Buffer.from(`--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.length}\r\n\r\n`),
    body,
    Buffer.from("\r\n"),
  ]);
}

test("MultipartFrameParser extracts frames split across arbitrary chunk boundaries", () => {
  const parser = new MultipartFrameParser(BOUNDARY);
  const whole = Buffer.concat([encodePart(frameBytes("frame-1")), encodePart(frameBytes("frame-2"))]);

  // 故意切在很怪的位置（header 中間、body 中間），模擬 TCP 不保證整段一起送達。
  const cut = 10;
  const first = parser.push(whole.subarray(0, cut));
  const rest = parser.push(whole.subarray(cut));

  assert.deepEqual(first, []);
  assert.deepEqual(
    rest.map((f) => f.toString()),
    ["frame-1", "frame-2"],
  );
});

test("MultipartFrameParser waits for more data when a frame is incomplete", () => {
  const parser = new MultipartFrameParser(BOUNDARY);
  const part = encodePart(frameBytes("hello"));
  assert.deepEqual(parser.push(part.subarray(0, part.length - 3)), []);
  assert.deepEqual(
    parser.push(part.subarray(part.length - 3)).map((f) => f.toString()),
    ["hello"],
  );
});

let server: Server;
let address: string;
/** 每個 test 各自控制自己那條連線何時收到 response、送幾幀、何時結束——用 URL path 分流。 */
const handlers = new Map<string, (res: ServerResponse) => void>();

before(async () => {
  server = createServer((req, res) => {
    const handler = req.url ? handlers.get(req.url) : undefined;
    if (!handler) {
      res.writeHead(404);
      res.end("not registered");
      return;
    }
    handler(res);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  address = `127.0.0.1:${port}`;
});

after(() => {
  server.close();
});

function waitForState(connection: MirrorConnection, status: MirrorConnectionState["status"]): Promise<MirrorConnectionState> {
  return new Promise((resolve) => {
    if (connection.state.status === status) {
      resolve(connection.state);
      return;
    }
    connection.onDidChangeState((state) => {
      if (state.status === status) resolve(state);
    });
  });
}

test("start reaches connected and forwards decoded frames in order", async () => {
  handlers.set("/mirror/5", (res) => {
    res.writeHead(200, { "content-type": `multipart/x-mixed-replace; boundary=${BOUNDARY}` });
    res.write(encodePart(frameBytes("a")));
    res.write(encodePart(frameBytes("b")));
    // 刻意不 res.end()：模擬鏡像串流正常情況下不會自己結束，靠 client 斷線或 stop() 收尾。
  });

  const connection = new MirrorConnection();
  const frames: string[] = [];
  connection.onDidReceiveFrame((frame) => frames.push(frame.toString()));
  connection.start(address, 5);

  await waitForState(connection, "connected");
  while (frames.length < 2) await delay(5);

  assert.deepEqual(frames, ["a", "b"]);
  connection.stop();
});

test("start against an unknown displayId reaches the error state (404)", async () => {
  handlers.set("/mirror/404", (res) => {
    res.writeHead(404);
    res.end("Unknown displayId");
  });

  const connection = new MirrorConnection();
  connection.start(address, 404);

  const state = await waitForState(connection, "error");
  assert.equal(state.status, "error");
  assert.match((state as { message: string }).message, /404/);
});

test("start against a closed port reaches the error state", async () => {
  const connection = new MirrorConnection();
  connection.start("127.0.0.1:1", 1);

  const state = await waitForState(connection, "error");
  assert.equal(state.status, "error");
});

test("stop aborts the in-flight stream and no further frames are delivered", async () => {
  let liveRes: ServerResponse | undefined;
  handlers.set("/mirror/9", (res) => {
    liveRes = res;
    res.writeHead(200, { "content-type": `multipart/x-mixed-replace; boundary=${BOUNDARY}` });
    res.write(encodePart(frameBytes("first")));
  });

  const connection = new MirrorConnection();
  const frames: string[] = [];
  connection.onDidReceiveFrame((frame) => frames.push(frame.toString()));
  connection.start(address, 9);
  await waitForState(connection, "connected");
  while (frames.length < 1) await delay(5);

  connection.stop();
  assert.equal(connection.state.status, "disconnected");

  // 停止之後，即使裝置端（測試裡是這個假 server）繼續送幀，也不該再被轉發出去，也不該
  // 讓連線改回其他狀態——確認底層的 fetch/reader 真的被中止，不是背景繼續拉流。
  liveRes?.write(encodePart(frameBytes("after-stop")));
  await delay(30);

  assert.deepEqual(frames, ["first"]);
  assert.equal(connection.state.status, "disconnected");
  liveRes?.end();
});

test("the stream ending on its own (device stopped serving) moves to disconnected", async () => {
  handlers.set("/mirror/7", (res) => {
    res.writeHead(200, { "content-type": `multipart/x-mixed-replace; boundary=${BOUNDARY}` });
    res.write(encodePart(frameBytes("only")));
    res.end(); // 裝置端主動關閉，模擬 WorkbenchServer.stop() 或鏡像畫面關閉。
  });

  const connection = new MirrorConnection();
  connection.start(address, 7);
  await waitForState(connection, "connected");

  const state = await waitForState(connection, "disconnected");
  assert.equal(state.status, "disconnected");
});

test("restarting after stop reconnects independently", async () => {
  handlers.set("/mirror/3", (res) => {
    res.writeHead(200, { "content-type": `multipart/x-mixed-replace; boundary=${BOUNDARY}` });
    res.write(encodePart(frameBytes("x")));
  });

  const connection = new MirrorConnection();
  connection.start(address, 3);
  await waitForState(connection, "connected");
  connection.stop();
  assert.equal(connection.state.status, "disconnected");

  connection.start(address, 3);
  const state = await waitForState(connection, "connected");
  assert.equal(state.status, "connected");
  connection.stop();
});
