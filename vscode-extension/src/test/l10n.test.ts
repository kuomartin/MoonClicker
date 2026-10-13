import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { formatText, msg } from "../message";

const root = join(__dirname, "..", "..");
const readJson = (file: string): Record<string, string> => JSON.parse(readFileSync(join(root, file), "utf8"));

/** `l10n.t(`、`msg(`、webview 的 `tr(` 第一個參數是字串字面值的呼叫；變數或運算式不在此列。 */
function literalKeys(source: string): string[] {
  const keys: string[] = [];
  const call = /(?:l10n\.t|\bmsg|\btr)\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/g;
  for (const [, literal] of source.matchAll(call)) {
    keys.push(literal.startsWith('"') ? JSON.parse(literal) : literal.slice(1, -1).replace(/\\(.)/g, "$1"));
  }
  return keys;
}

test("every localizable string has a zh-tw translation", () => {
  const bundle = readJson("l10n/bundle.l10n.zh-tw.json");
  const sources = [
    ...readdirSync(join(root, "src"))
      .filter((f) => f.endsWith(".ts"))
      .map((f) => join(root, "src", f)),
    join(root, "media", "mirror.js"),
  ];
  const keys = sources.flatMap((file) => literalKeys(readFileSync(file, "utf8")));
  assert.ok(keys.length > 100);
  assert.deepEqual(
    keys.filter((key) => !(key in bundle)),
    [],
  );
});

test("package.nls files have the same keys", () => {
  assert.deepEqual(Object.keys(readJson("package.nls.zh-tw.json")).sort(), Object.keys(readJson("package.nls.json")).sort());
});

test("formatText fills placeholders and leaves raw strings alone", () => {
  assert.equal(formatText(msg("Failed to run (HTTP {0}): {1}", 500, "boom")), "Failed to run (HTTP 500): boom");
  assert.equal(formatText("{0} from the device"), "{0} from the device");
});
