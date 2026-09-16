import * as assert from "node:assert";
import { test, suite } from "node:test";
import { MirrorConnection, type MirrorConnectionState } from "../mirrorConnection";

suite("MirrorConnection", () => {
  test("initial state is disconnected", () => {
    const conn = new MirrorConnection();
    assert.deepEqual(conn.state, { status: "disconnected" });
  });
});
