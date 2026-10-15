import { randomUUID } from "node:crypto";
import * as vscode from "vscode";
import { ReplChunkEvent_Status, type ReplChunkEvent, type ReplStateEvent } from "./generated/workbench_stream_event_pb";
import { evalRepl, interruptRepl, LineEditor, toTerminal, type ReplResult, type ReplStatus } from "./repl";
import { errorText } from "./l10n";

const PROMPT = "> ";
const CONTINUATION = ">> ";
const RED = "\x1b[31m";
const YELLOW = "\x1b[33m";
const DIM = "\x1b[2m";
const RESET = "\x1b[0m";

export interface ReplEndpoint {
  address: string;
  token?: string;
}

/**
 * 裝置上 REPL 的終端機。輸入一行就送出；語法沒寫完時以 `>>` 接著輸入，與官方 `lua` 相同。
 * Ctrl+C 在有程式碼跑著時中斷那一段，否則放棄目前這一行。
 *
 * 接上別的連線（MCP）開的 REPL 時，對方跑的程式碼與結果從串流進來，也印在這裡。
 */
export class ReplTerminal implements vscode.Pseudoterminal {
  /** 隨每段程式碼送出，串流回來時用來分辨是不是自己送的。 */
  readonly client = `vscode-${randomUUID()}`;
  /** 這個終端機接著的 REPL。 */
  readonly replId: string;

  private readonly writeEmitter = new vscode.EventEmitter<string>();
  private readonly closeEmitter = new vscode.EventEmitter<void>();
  readonly onDidWrite = this.writeEmitter.event;
  readonly onDidClose = this.closeEmitter.event;

  private readonly editor = new LineEditor(PROMPT);
  /** 還沒寫完的多行程式碼。 */
  private pending: string | undefined;
  /** 自己送出、還沒回來的段數。 */
  private inFlight = 0;
  /** 依序送出：前一段跑完才送下一段。 */
  private queue: Promise<void> = Promise.resolve();
  private deviceBusy = false;
  private open_ = true;

  constructor(
    private readonly endpoint: () => ReplEndpoint | undefined,
    private readonly status: ReplStatus,
    private readonly onClosed: () => void,
    /** Ctrl+D 在空行上：關閉裝置上的 REPL。 */
    private readonly onEof: () => void,
  ) {
    this.replId = status.id;
  }

  open(): void {
    const where = this.status.scriptId
      ? vscode.l10n.t("MoonClicker REPL on display {0}, scripts root \"{1}\"", this.status.displayId, this.status.scriptId)
      : vscode.l10n.t("MoonClicker REPL on display {0}", this.status.displayId);
    this.writeEmitter.fire(
      `${DIM}${where}\r\n` +
        vscode.l10n.t("Globals persist until reset; Ctrl+C interrupts the running code; Ctrl+D closes the REPL.") +
        `${RESET}\r\n${this.editor.render()}`,
    );
  }

  close(): void {
    this.onClosed();
  }

  dispose(): void {
    this.closeEmitter.fire();
  }

  handleInput(data: string): void {
    const { output, actions } = this.editor.feed(data);
    if (output) this.writeEmitter.fire(output);
    for (const action of actions) {
      if (action.kind === "submit") {
        this.submitLine(action.line);
      } else if (action.kind === "eof") {
        // 續行中只丟掉沒寫完的那段，有程式碼在跑時不理：誤按的代價是整個 REPL 被關掉。
        if (this.inFlight > 0 || this.deviceBusy) continue;
        if (this.pending !== undefined) {
          this.discardPending();
        } else {
          this.writeEmitter.fire("\r\n");
          this.onEof();
        }
      } else if (this.inFlight > 0 || this.deviceBusy) {
        this.interrupt();
      } else {
        this.discardPending();
      }
    }
  }

  private discardPending(): void {
    this.pending = undefined;
    this.editor.prompt = PROMPT;
    this.writeEmitter.fire(this.editor.discard() + this.editor.render());
  }

  /** 從編輯器送來的程式碼：整段送出，不走續行。 */
  runCode(code: string): void {
    const echo = code.split(/\r?\n/).map((line, i) => (i === 0 ? PROMPT : CONTINUATION) + line).join("\n");
    this.print(echo);
    this.enqueue(code);
  }

  interrupt(): void {
    const endpoint = this.endpoint();
    if (!endpoint) return;
    interruptRepl(endpoint.address, this.replId, endpoint.token).catch((err) => this.printError(errorText(err)));
  }

  printLog(line: string): void {
    this.print(line);
  }

  /** 別的連線跑的程式碼。 */
  printForeign(chunk: ReplChunkEvent): void {
    if (chunk.status === ReplChunkEvent_Status.INCOMPLETE) return;
    const source = chunk.client ? `[${chunk.client}] ` : "";
    this.print(`${DIM}${source}${chunk.code}${RESET}`);
    this.printResult({ status: statusName(chunk.status), output: chunk.output });
  }

  /** 裝置上開著的 REPL 清單；自己這個不在裡面就是被關掉了（例如被別的連線關掉）。 */
  onList(repls: ReplStateEvent[]): void {
    const mine = repls.find((repl) => repl.id === this.replId);
    this.deviceBusy = mine?.busy ?? false;
    if (!mine && this.open_) this.print(`${YELLOW}${vscode.l10n.t("The REPL on the device was closed.")}${RESET}`);
    this.open_ = mine !== undefined;
  }

  /** 在提示字元上方印一段文字，再把正在輸入的那一行畫回來。 */
  print(text: string): void {
    this.writeEmitter.fire(this.editor.clearLine() + toTerminal(text) + "\r\n" + this.editor.render());
  }

  private printError(text: string): void {
    this.print(`${RED}${text}${RESET}`);
  }

  private printResult(result: ReplResult): void {
    switch (result.status) {
      case "ok":
        if (result.output !== "") this.print(result.output);
        break;
      case "error":
      case "incomplete":
        this.printError(result.output);
        break;
      case "interrupted":
        this.print(`${YELLOW}${vscode.l10n.t("Interrupted")}${RESET}`);
        break;
      case "closed":
        this.print(`${YELLOW}${vscode.l10n.t("The REPL on the device was closed.")}${RESET}`);
        break;
    }
  }

  private submitLine(line: string): void {
    const code = this.pending === undefined ? line : `${this.pending}\n${line}`;
    if (this.pending === undefined && code.trim() === "") {
      this.writeEmitter.fire(this.editor.render());
      return;
    }
    this.enqueue(code, true);
  }

  private enqueue(code: string, typed = false): void {
    this.inFlight++;
    this.queue = this.queue.then(async () => {
      const endpoint = this.endpoint();
      try {
        if (!endpoint) throw new Error(vscode.l10n.t("MoonClicker: Not connected to a device"));
        const result = await evalRepl(endpoint.address, this.replId, code, this.client, endpoint.token);
        if (typed && result.status === "incomplete") {
          this.pending = code;
          this.editor.prompt = CONTINUATION;
          return;
        }
        if (typed) {
          this.pending = undefined;
          this.editor.prompt = PROMPT;
        }
        this.printResult(result);
      } catch (err) {
        if (typed) {
          this.pending = undefined;
          this.editor.prompt = PROMPT;
        }
        this.printError(errorText(err));
      } finally {
        this.inFlight--;
        // 沒有輸出時（例如賦值）也要把提示字元畫回來。
        if (typed) this.writeEmitter.fire(this.editor.render());
      }
    });
  }
}

function statusName(status: ReplChunkEvent_Status): ReplResult["status"] {
  switch (status) {
    case ReplChunkEvent_Status.OK:
      return "ok";
    case ReplChunkEvent_Status.INCOMPLETE:
      return "incomplete";
    case ReplChunkEvent_Status.INTERRUPTED:
      return "interrupted";
    case ReplChunkEvent_Status.CLOSED:
      return "closed";
    default:
      return "error";
  }
}
