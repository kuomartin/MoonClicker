import { authHeaders } from "./authSync";
import { MessageError, msg } from "./message";

/** 一個 REPL 的狀態：`GET /repl` 的每一筆，也是 open/reset 的回應。 */
export interface ReplStatus {
  id: string;
  displayId: number;
  /** `require` 與模板路徑的根；省略表示整個 scripts 資料夾。 */
  scriptId?: string;
  busy: boolean;
}

export type ReplResultStatus = "ok" | "error" | "incomplete" | "interrupted" | "closed";

export interface ReplResult {
  status: ReplResultStatus;
  /** ok 時是回傳值（以 tab 串起），其餘是錯誤訊息。 */
  output: string;
}

async function post(address: string, path: string, token?: string, body?: unknown): Promise<Response> {
  const response = await fetch(`http://${address}${path}`, {
    method: "POST",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const reason = await response.text();
    throw new MessageError(msg("REPL request failed (HTTP {0}): {1}", response.status, reason));
  }
  return response;
}

/** 裝置上所有開著的 REPL，依開啟順序。 */
export async function listRepls(address: string, token?: string): Promise<ReplStatus[]> {
  const response = await fetch(`http://${address}/repl`, { headers: authHeaders(token) });
  if (!response.ok) throw new MessageError(msg("REPL request failed (HTTP {0}): {1}", response.status, await response.text()));
  return (await response.json()) as ReplStatus[];
}

/** 開一個新的 REPL，不影響裝置上已經開著的。 */
export async function openRepl(address: string, displayId: number, scriptId: string | undefined, token?: string): Promise<ReplStatus> {
  return (await (await post(address, "/repl/open", token, { displayId, scriptId })).json()) as ReplStatus;
}

function replPath(id: string, action: string): string {
  return `/repl/${encodeURIComponent(id)}/${action}`;
}

export async function resetRepl(address: string, id: string, token?: string): Promise<ReplStatus> {
  return (await (await post(address, replPath(id, "reset"), token)).json()) as ReplStatus;
}

export async function closeRepl(address: string, id: string, token?: string): Promise<void> {
  await post(address, replPath(id, "close"), token);
}

export async function interruptRepl(address: string, id: string, token?: string): Promise<void> {
  await post(address, replPath(id, "interrupt"), token);
}

/** 等這段跑完才回來。[client] 隨串流的 chunk 事件帶回來，用來分辨自己送的。 */
export async function evalRepl(address: string, id: string, code: string, client: string, token?: string): Promise<ReplResult> {
  return (await (await post(address, replPath(id, "eval"), token, { code, client })).json()) as ReplResult;
}

/** 終端機的換行：Pseudoterminal 要 CRLF。 */
export function toTerminal(text: string): string {
  return text.replace(/\r?\n/g, "\r\n");
}

/** [LineEditor.feed] 要呼叫端做的事。 */
export type LineAction =
  | { kind: "submit"; line: string }
  | { kind: "interrupt" }
  | { kind: "eof" };

/**
 * REPL 終端機的單行編輯：游標移動、退格、歷史。不依賴 vscode，餵進終端機收到的原始輸入，
 * 回傳要寫回終端機的字串與要做的事。貼上多行時每一行各自送出，與在 `lua` 裡貼上相同。
 */
export class LineEditor {
  private buffer = "";
  private cursor = 0;
  private readonly history: string[] = [];
  /** 正在看的歷史項目；等於 history.length 表示在編輯新的一行。 */
  private historyIndex = 0;
  /** 往回翻歷史前正在打的那一行。 */
  private draft = "";

  constructor(public prompt: string = "> ") {}

  get line(): string {
    return this.buffer;
  }

  /** 重畫目前這一行（提示字元加內容），游標放回原位。 */
  render(): string {
    const back = this.buffer.length - this.cursor;
    return `\r\x1b[K${this.prompt}${this.buffer}` + (back > 0 ? `\x1b[${back}D` : "");
  }

  /** 清掉目前這一行的顯示，給印 log 之類用；之後要再 [render]。 */
  clearLine(): string {
    return "\r\x1b[K";
  }

  feed(data: string): { output: string; actions: LineAction[] } {
    let output = "";
    const actions: LineAction[] = [];
    let i = 0;
    while (i < data.length) {
      const rest = data.slice(i);
      const escape = /^\x1b\[([ABCDHF]|[134]~)/.exec(rest);
      if (escape) {
        output += this.handleEscape(escape[1]);
        i += escape[0].length;
        continue;
      }
      const ch = data[i];
      i++;
      if (ch === "\r" || ch === "\n") {
        // \r\n 只算一次換行；有些貼上只帶 \n。
        if (ch === "\r" && data[i] === "\n") i++;
        const line = this.buffer;
        if (line.trim() !== "" && this.history[this.history.length - 1] !== line) this.history.push(line);
        this.historyIndex = this.history.length;
        this.draft = "";
        this.buffer = "";
        this.cursor = 0;
        output += "\r\n";
        actions.push({ kind: "submit", line });
      } else if (ch === "\x03") {
        actions.push({ kind: "interrupt" });
      } else if (ch === "\x04") {
        // 與 readline 相同：空行是 EOF，否則刪掉游標上的字。
        if (this.buffer === "") actions.push({ kind: "eof" });
        else output += this.handleEscape("3~");
      } else if (ch === "\x7f" || ch === "\b") {
        if (this.cursor > 0) {
          this.buffer = this.buffer.slice(0, this.cursor - 1) + this.buffer.slice(this.cursor);
          this.cursor--;
          output += this.render();
        }
      } else if (ch === "\x01") {
        this.cursor = 0;
        output += this.render();
      } else if (ch === "\x05") {
        this.cursor = this.buffer.length;
        output += this.render();
      } else if (ch === "\x15") {
        this.buffer = this.buffer.slice(this.cursor);
        this.cursor = 0;
        output += this.render();
      } else if (ch >= " " || ch === "\t") {
        this.buffer = this.buffer.slice(0, this.cursor) + ch + this.buffer.slice(this.cursor);
        this.cursor++;
        output += this.cursor === this.buffer.length ? ch : this.render();
      }
    }
    return { output, actions };
  }

  /** 放棄目前這一行（Ctrl+C 且沒有東西在跑時）。 */
  discard(): string {
    this.buffer = "";
    this.cursor = 0;
    this.historyIndex = this.history.length;
    return "^C\r\n";
  }

  private handleEscape(code: string): string {
    switch (code) {
      case "A":
        return this.showHistory(this.historyIndex - 1);
      case "B":
        return this.showHistory(this.historyIndex + 1);
      case "C":
        if (this.cursor >= this.buffer.length) return "";
        this.cursor++;
        return "\x1b[C";
      case "D":
        if (this.cursor <= 0) return "";
        this.cursor--;
        return "\x1b[D";
      case "H":
      case "1~":
        this.cursor = 0;
        return this.render();
      case "F":
      case "4~":
        this.cursor = this.buffer.length;
        return this.render();
      case "3~":
        if (this.cursor >= this.buffer.length) return "";
        this.buffer = this.buffer.slice(0, this.cursor) + this.buffer.slice(this.cursor + 1);
        return this.render();
      default:
        return "";
    }
  }

  private showHistory(index: number): string {
    if (index < 0 || index > this.history.length) return "";
    if (this.historyIndex === this.history.length) this.draft = this.buffer;
    this.historyIndex = index;
    this.buffer = index === this.history.length ? this.draft : this.history[index];
    this.cursor = this.buffer.length;
    return this.render();
  }
}
