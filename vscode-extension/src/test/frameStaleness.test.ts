import assert from "node:assert/strict";
import { test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { FrameStalenessTracker, type StalenessState } from "../frameStaleness";

const STALE_AFTER_MS = 20;

function tracked(): { tracker: FrameStalenessTracker; states: StalenessState[] } {
  const states: StalenessState[] = [];
  const tracker = new FrameStalenessTracker(STALE_AFTER_MS, (state) => states.push(state));
  return { tracker, states };
}

test("stays silent until the arm timeout elapses without a frame", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();

  await delay(STALE_AFTER_MS / 2);
  assert.deepEqual(states, []);

  await delay(STALE_AFTER_MS);
  assert.deepEqual(states, ["stale"]);
});

test("a frame arriving before the timeout reports fresh and postpones stale", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();

  await delay(STALE_AFTER_MS / 2);
  tracker.noteFrame();
  assert.deepEqual(states, ["fresh"]);

  await delay(STALE_AFTER_MS / 2);
  assert.deepEqual(states, ["fresh"]); // 還沒到重新起算後的逾時

  await delay(STALE_AFTER_MS);
  assert.deepEqual(states, ["fresh", "stale"]);
});

test("frames arriving faster than the timeout never go stale", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();

  for (let i = 0; i < 5; i++) {
    await delay(STALE_AFTER_MS / 3);
    tracker.noteFrame();
  }

  assert.deepEqual(states, ["fresh", "fresh", "fresh", "fresh", "fresh"]);
});

test("disarm cancels a pending stale timeout", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();

  await delay(STALE_AFTER_MS / 2);
  tracker.disarm();

  await delay(STALE_AFTER_MS);
  assert.deepEqual(states, []);
});

test("disarm after a frame also cancels the timeout it scheduled", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();
  tracker.noteFrame();
  tracker.disarm();

  await delay(STALE_AFTER_MS * 2);
  assert.deepEqual(states, ["fresh"]);
});

test("re-arming after a stale report can report stale again", async () => {
  const { tracker, states } = tracked();
  tracker.armFromConnect();

  await delay(STALE_AFTER_MS * 1.5);
  assert.deepEqual(states, ["stale"]);

  tracker.armFromConnect();
  await delay(STALE_AFTER_MS * 1.5);
  assert.deepEqual(states, ["stale", "stale"]);
});
