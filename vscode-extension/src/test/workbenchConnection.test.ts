import assert from "node:assert/strict";
import { test } from "node:test";
import { WebSocketServer } from "ws";
import { ConnectionState, WorkbenchConnection } from "../workbenchConnection";

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
