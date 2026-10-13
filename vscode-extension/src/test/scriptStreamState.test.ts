import assert from "node:assert/strict";
import { test } from "node:test";
import { create } from "@bufbuild/protobuf";
import { DataEventSchema } from "../generated/workbench_stream_event_pb";
import { ScriptDataStore } from "../scriptStreamState";

const event = (init: Parameters<typeof create<typeof DataEventSchema>>[1]) => create(DataEventSchema, init);

test("a reset replaces the script's data", () => {
  const store = new ScriptDataStore();
  store.apply(event({ scriptId: "a", reset: true, values: { old: 1 } }));

  const data = store.apply(event({ scriptId: "a", reset: true, values: { fresh: true } }));

  assert.deepEqual(data, { fresh: true });
});

test("a diff updates changed keys and drops removed ones", () => {
  const store = new ScriptDataStore();
  store.apply(event({ scriptId: "a", reset: true, values: { progress: 0.25, status: "busy", stale: 1 } }));

  const data = store.apply(event({ scriptId: "a", values: { progress: 0.5 }, removed: ["stale"] }));

  assert.deepEqual(data, { progress: 0.5, status: "busy" });
});

test("scripts keep separate data", () => {
  const store = new ScriptDataStore();
  store.apply(event({ scriptId: "a", reset: true, values: { n: 1 } }));
  store.apply(event({ scriptId: "b", reset: true, values: { n: 2 } }));

  assert.deepEqual(store.all(), { a: { n: 1 }, b: { n: 2 } });
});
