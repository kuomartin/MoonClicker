import assert from "node:assert/strict";
import { test } from "node:test";
import { createSyncFilter } from "../syncIgnore";

test("without an ignore file only the defaults apply", () => {
  const ignored = createSyncFilter(undefined);
  assert.equal(ignored(".luarc.json", false), true);
  assert.equal(ignored(".moonclickerignore", false), true);
  assert.equal(ignored(".git", true), true);
  assert.equal(ignored(".git/HEAD", false), true);
  assert.equal(ignored(".vscode/settings.json", false), true);
  assert.equal(ignored("main.lua", false), false);
  assert.equal(ignored("templates/btn.png", false), false);
});

test("file patterns match at any depth", () => {
  const ignored = createSyncFilter("*.psd\n");
  assert.equal(ignored("art.psd", false), true);
  assert.equal(ignored("templates/raw/art.psd", false), true);
  assert.equal(ignored("templates/btn.png", false), false);
});

test("a trailing slash only matches directories and everything under them", () => {
  const ignored = createSyncFilter("build/\n");
  assert.equal(ignored("build", true), true);
  assert.equal(ignored("build", false), false);
  assert.equal(ignored("build/out.txt", false), true);
  assert.equal(ignored("src/build", true), true);
});

test("negation re-includes a file excluded by an earlier rule", () => {
  const ignored = createSyncFilter("*.psd\n!keep.psd\n");
  assert.equal(ignored("art.psd", false), true);
  assert.equal(ignored("keep.psd", false), false);
});

test("comments and blank lines are ignored", () => {
  const ignored = createSyncFilter("# drafts\n\ndrafts/\n");
  assert.equal(ignored("drafts", true), true);
  assert.equal(ignored("# drafts", false), false);
});

test("the user can negate a default rule", () => {
  const ignored = createSyncFilter("!.luarc.json\n");
  assert.equal(ignored(".luarc.json", false), false);
});
