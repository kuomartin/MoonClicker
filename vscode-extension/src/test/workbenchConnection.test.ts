import assert from "node:assert/strict";
import { test } from "node:test";
import { WebSocketServer } from "ws";
import { create, toBinary } from "@bufbuild/protobuf";
import { StreamEventSchema } from "../generated/workbench_stream_event_pb";
import { formatText } from "../message";
import { ConnectionState, PROTOCOL_VERSION, WorkbenchConnection, parseStreamEvent } from "../workbenchConnection";

function encode(event: Parameters<typeof create<typeof StreamEventSchema>>[1]): Uint8Array {
  return toBinary(StreamEventSchema, create(StreamEventSchema, event));
}

test("parseStreamEvent recognizes a log envelope", () => {
  const event = parseStreamEvent(encode({ event: { case: "log", value: { scriptId: "demo", line: "hi" } } }));
  assert.equal(event?.event.case, "log");
  assert.equal(event?.event.case === "log" ? event.event.value.line : undefined, "hi");
  assert.equal(event?.event.case === "log" ? event.event.value.scriptId : undefined, "demo");
});

test("parseStreamEvent recognizes a data envelope", () => {
  const event = parseStreamEvent(encode({ event: { case: "data", value: { scriptId: "demo", reset: true, values: { k: "v" } } } }));
  assert.equal(event?.event.case, "data");
  assert.deepEqual(event?.event.case === "data" ? event.event.value.values : undefined, { k: "v" });
});

test("parseStreamEvent ignores malformed bytes", () => {
  // field number 0 不是合法的 protobuf 欄位編號，binary decode 會直接丟例外。
  assert.equal(parseStreamEvent(new Uint8Array([0, 0])), undefined);
});

function withServer(fn: (port: number) => Promise<void>): Promise<void> {
  return new Promise((resolve, reject) => {
    const server = new WebSocketServer({ port: 0 });
    server.on("listening", async () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      try {
        await fn(port);
        resolve();
      } catch (err) {
        reject(err);
      } finally {
        server.close();
      }
    });
  });
}

function waitForState(
  connection: WorkbenchConnection,
  status: ConnectionState["status"],
): Promise<ConnectionState> {
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

test("connect reaches the connected state against a real server", async () => {
  await withServer(async (port) => {
    const connection = new WorkbenchConnection();
    connection.connect(`127.0.0.1:${port}`);

    const state = await waitForState(connection, "connected");

    assert.equal(state.status, "connected");
    connection.disconnect();
  });
});

test("connect to a closed port reaches the error state", async () => {
  const connection = new WorkbenchConnection();
  connection.connect("127.0.0.1:1");

  const state = await waitForState(connection, "error");

  assert.equal(state.status, "error");
});

test("connection ignores text frames instead of decoding them as protobuf", async () => {
  // 文字 frame 是 #57 的連線 echo，不是 StreamEvent——decode 任意位元組不保證丟例外，
  // 混進來會被誤判成一筆假造的事件。
  await new Promise<void>((resolve, reject) => {
    const server = new WebSocketServer({ port: 0 });
    server.on("listening", () => {
      const port = (server.address() as { port: number }).port;
      server.on("connection", (ws) => {
        ws.send("not a stream event");
        ws.send(encode({ event: { case: "data", value: { scriptId: "demo", reset: true, values: { status: "claimed" } } } }));
      });

      const connection = new WorkbenchConnection();
      connection.onDidReceiveStreamEvent((event) => {
        try {
          assert.equal(event.event.case, "data");
          connection.disconnect();
          server.close();
          resolve();
        } catch (err) {
          reject(err);
        }
      });
      connection.connect(`127.0.0.1:${port}`);
    });
  });
});

test("connection forwards parsed stream events to listeners", async () => {
  await new Promise<void>((resolve, reject) => {
    const server = new WebSocketServer({ port: 0 });
    server.on("listening", () => {
      const port = (server.address() as { port: number }).port;
      server.on("connection", (ws) => {
        ws.send(encode({ event: { case: "data", value: { scriptId: "demo", reset: true, values: { status: "claimed" } } } }));
      });

      const connection = new WorkbenchConnection();
      const received: unknown[] = [];
      connection.onDidReceiveStreamEvent((event) => {
        received.push(event);
        if (received.length === 1) {
          try {
            assert.equal(event.event.case, "data");
            assert.deepEqual(
              event.event.case === "data" ? event.event.value.values : undefined,
              { status: "claimed" },
            );
            connection.disconnect();
            server.close();
            resolve();
          } catch (err) {
            reject(err);
          }
        }
      });
      connection.connect(`127.0.0.1:${port}`);
    });
  });
});

test("disconnect after connecting resets to disconnected and can reconnect", async () => {
  await withServer(async (port) => {
    const connection = new WorkbenchConnection();
    connection.connect(`127.0.0.1:${port}`);
    await waitForState(connection, "connected");

    connection.disconnect();
    assert.equal(connection.state.status, "disconnected");

    connection.connect(`127.0.0.1:${port}`);
    const state = await waitForState(connection, "connected");
    assert.equal(state.status, "connected");
    connection.disconnect();
  });
});

test("connect sends the protocol version", async () => {
  await withServerUrl(async (port, requestUrl) => {
    const connection = new WorkbenchConnection();
    connection.connect(`127.0.0.1:${port}`, "secret");
    await waitForState(connection, "connected");

    const url = new URL(await requestUrl, "http://localhost");
    assert.equal(url.searchParams.get("protocol"), String(PROTOCOL_VERSION));
    assert.equal(url.searchParams.get("token"), "secret");
    connection.disconnect();
  });
});

function withServerUrl(fn: (port: number, requestUrl: Promise<string>) => Promise<void>): Promise<void> {
  return new Promise((resolve, reject) => {
    const server = new WebSocketServer({ port: 0 });
    const requestUrl = new Promise<string>((res) => server.on("connection", (_ws, req) => res(req.url ?? "")));
    server.on("listening", async () => {
      const port = (server.address() as { port: number }).port;
      try {
        await fn(port, requestUrl);
        resolve();
      } catch (err) {
        reject(err);
      } finally {
        server.close();
      }
    });
  });
}

test("a protocol mismatch close reaches the error state with the reason", async () => {
  await new Promise<void>((resolve, reject) => {
    const server = new WebSocketServer({ port: 0 });
    server.on("listening", async () => {
      const port = (server.address() as { port: number }).port;
      server.on("connection", (ws) => ws.close(1008, "Workbench protocol mismatch: app 3, extension 2"));
      const connection = new WorkbenchConnection();
      connection.connect(`127.0.0.1:${port}`);
      try {
        const state = await waitForState(connection, "error");
        assert.ok(state.status === "error" && formatText(state.message).includes("app 3"));
        resolve();
      } catch (err) {
        reject(err);
      } finally {
        connection.disconnect();
        server.close();
      }
    });
  });
});
