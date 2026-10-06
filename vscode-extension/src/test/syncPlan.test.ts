import assert from "node:assert/strict";
import { test } from "node:test";
import { planSync, relPathOf, scriptIdOf } from "../syncPlan";

const K = "demo/main.lua";
const none = () => false;

function plan(b?: string, l?: string, r?: string, ignored = false) {
  const m = (v?: string) => new Map(v === undefined ? [] : [[K, v]]);
  return planSync(m(b), m(l), m(r), () => ignored);
}

test("identical everywhere needs nothing", () => {
  const p = plan("a", "a", "a");
  assert.deepEqual(p.actions, []);
  assert.equal(p.inSync.size, 0);
});

test("only the device changed: pull", () => {
  assert.deepEqual(plan("a", "a", "b").actions, [{ kind: "pull", key: K }]);
});

test("only local changed: push", () => {
  assert.deepEqual(plan("a", "b", "a").actions, [{ kind: "push", key: K }]);
});

test("new on the device: pull", () => {
  assert.deepEqual(plan(undefined, undefined, "a").actions, [{ kind: "pull", key: K }]);
});

test("new locally: push", () => {
  assert.deepEqual(plan(undefined, "a", undefined).actions, [{ kind: "push", key: K }]);
});

test("deleted on the device, untouched locally: delete local", () => {
  assert.deepEqual(plan("a", "a", undefined).actions, [{ kind: "deleteLocal", key: K }]);
});

test("deleted locally, untouched on the device: delete remote", () => {
  assert.deepEqual(plan("a", undefined, "a").actions, [{ kind: "deleteRemote", key: K }]);
});

test("both changed differently: conflict", () => {
  assert.deepEqual(plan("a", "b", "c").actions, [{ kind: "conflict", key: K }]);
});

test("both created differently without a base: conflict", () => {
  assert.deepEqual(plan(undefined, "b", "c").actions, [{ kind: "conflict", key: K }]);
});

test("deleted locally but modified on the device: restore from remote", () => {
  assert.deepEqual(plan("a", undefined, "b").actions, [{ kind: "restore", key: K, from: "remote" }]);
});

test("deleted on the device but modified locally: restore from local", () => {
  assert.deepEqual(plan("a", "b", undefined).actions, [{ kind: "restore", key: K, from: "local" }]);
});

test("both made the same change: only the base moves", () => {
  const p = plan("a", "b", "b");
  assert.deepEqual(p.actions, []);
  assert.deepEqual([...p.inSync], [[K, "b"]]);
});

test("both deleted: the base entry is dropped", () => {
  const p = plan("a", undefined, undefined);
  assert.deepEqual(p.actions, []);
  assert.deepEqual([...p.inSync], [[K, undefined]]);
});

test("ignored files are never pushed or deleted remotely", () => {
  assert.deepEqual(plan(undefined, "a", undefined, true).actions, []);
  assert.deepEqual(plan("a", "b", "a", true).actions, []);
  assert.deepEqual(plan("a", undefined, "a", true).actions, []);
  assert.deepEqual(plan("a", "b", undefined, true).actions, []);
});

test("ignored files are not deleted locally when the device drops them", () => {
  assert.deepEqual(plan("a", "a", undefined, true).actions, []);
});

test("ignored files still come down from the device", () => {
  assert.deepEqual(plan("a", "a", "b", true).actions, [{ kind: "pull", key: K }]);
  assert.deepEqual(plan("a", "b", "c", true).actions, [{ kind: "conflict", key: K }]);
  assert.deepEqual(plan("a", undefined, "b", true).actions, [{ kind: "restore", key: K, from: "remote" }]);
});

test("actions come out sorted by key", () => {
  const p = planSync(new Map(), new Map([["b/main.lua", "1"], ["a/main.lua", "2"]]), new Map(), none);
  assert.deepEqual(p.actions.map((a) => a.key), ["a/main.lua", "b/main.lua"]);
});

test("key helpers split the script id from the relative path", () => {
  assert.equal(scriptIdOf("demo/img/btn.png"), "demo");
  assert.equal(relPathOf("demo/img/btn.png"), "img/btn.png");
});
