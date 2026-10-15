import assert from "node:assert/strict";
import { createServer, Server } from "node:http";
import { after, before, test } from "node:test";
import { evalRepl, LineEditor, listRepls, openRepl, resetRepl, toTerminal } from "../repl";

let server: Server;
let address: string;
let lastBody: unknown;

before(async () => {
  server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      lastBody = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : undefined;
      if (req.url === "/repl" && req.method === "GET") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify([{ id: "1", displayId: 0, busy: true }]));
      } else if (req.url === "/repl/open" && req.method === "POST") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ id: "2", displayId: 3, busy: false }));
      } else if (req.url === "/repl/2/eval" && req.method === "POST") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ status: "ok", output: "2" }));
      } else {
        res.writeHead(404);
        res.end("REPL 9 is not open");
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  address = `127.0.0.1:${port}`;
});

after(() => server.close());

test("list, open and eval talk to the REPL routes", async () => {
  assert.deepEqual(await listRepls(address), [{ id: "1", displayId: 0, busy: true }]);

  const opened = await openRepl(address, 3, "demo");
  assert.equal(opened.id, "2");
  assert.deepEqual(lastBody, { displayId: 3, scriptId: "demo" });

  assert.deepEqual(await evalRepl(address, opened.id, "1 + 1", "vscode-1"), { status: "ok", output: "2" });
  assert.deepEqual(lastBody, { code: "1 + 1", client: "vscode-1" });
});

test("a failed request carries the device's reason", async () => {
  await assert.rejects(() => resetRepl(address, "9"), /REPL 9 is not open/);
});

test("typing and enter submit the line", () => {
  const editor = new LineEditor();
  const { output, actions } = editor.feed("x = 1\r");
  assert.equal(output, "x = 1\r\n");
  assert.deepEqual(actions, [{ kind: "submit", line: "x = 1" }]);
});

test("pasted lines are submitted one by one", () => {
  const editor = new LineEditor();
  const { actions } = editor.feed("for i = 1, 2 do\nlog(i)\r\nend\r");
  assert.deepEqual(
    actions.map((a) => (a.kind === "submit" ? a.line : a.kind)),
    ["for i = 1, 2 do", "log(i)", "end"],
  );
});

test("backspace and cursor movement edit in place", () => {
  const editor = new LineEditor();
  editor.feed("abd");
  editor.feed("\x1b[D");
  editor.feed("\x7f");
  editor.feed("c");
  assert.equal(editor.line, "acd");
});

test("history walks back and returns to the draft", () => {
  const editor = new LineEditor();
  editor.feed("first\r");
  editor.feed("second\r");
  editor.feed("draft");
  editor.feed("\x1b[A");
  assert.equal(editor.line, "second");
  editor.feed("\x1b[A");
  assert.equal(editor.line, "first");
  editor.feed("\x1b[B\x1b[B");
  assert.equal(editor.line, "draft");
});

test("ctrl+c asks to interrupt", () => {
  const editor = new LineEditor();
  assert.deepEqual(editor.feed("\x03").actions, [{ kind: "interrupt" }]);
});

test("ctrl+d closes on an empty line and deletes forward otherwise", () => {
  const editor = new LineEditor();
  assert.deepEqual(editor.feed("\x04").actions, [{ kind: "eof" }]);
  editor.feed("abc\x1b[D\x1b[D");
  assert.deepEqual(editor.feed("\x04").actions, []);
  assert.equal(editor.line, "ac");
});

test("terminal output uses CRLF", () => {
  assert.equal(toTerminal("a\nb\r\nc"), "a\r\nb\r\nc");
});
