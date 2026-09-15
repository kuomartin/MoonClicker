import WebSocket from "ws";

export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting"; address: string }
  | { status: "connected"; address: string }
  | { status: "error"; address: string; message: string };

/** 裝置端串流過來的兩種訊息（見 #62），用 `type` 判別，不用猜形狀。 */
export type StreamEvent =
  | { type: "log"; line: string }
  | { type: "data"; data: Record<string, unknown> };

/**
 * 解析一筆原始 WebSocket 訊息。不是合法 envelope（milestone 1 沒有其他訊息格式）就回傳
 * `undefined`，呼叫端直接忽略——不是這個連線該處理的東西，不代表連線壞了。
 */
export function parseStreamEvent(raw: string): StreamEvent | undefined {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (typeof parsed !== "object" || parsed === null) return undefined;
  const obj = parsed as Record<string, unknown>;
  if (obj.type === "log" && typeof obj.line === "string") {
    return { type: "log", line: obj.line };
  }
  if (obj.type === "data" && typeof obj.data === "object" && obj.data !== null) {
    return { type: "data", data: obj.data as Record<string, unknown> };
  }
  return undefined;
}

/**
 * WebSocket 連線的純邏輯（不依賴 vscode API），單元測試不需要跑在 VS Code extension host 裡。
 * `extension.ts` 只負責把 [state] 與串流事件接到狀態列與輸出面板上。
 */
export class WorkbenchConnection {
  private socket: WebSocket | undefined;
  private _state: ConnectionState = { status: "disconnected" };
  private listeners: Array<(state: ConnectionState) => void> = [];
  private streamListeners: Array<(event: StreamEvent) => void> = [];

  onDidReceiveStreamEvent(listener: (event: StreamEvent) => void): void {
    this.streamListeners.push(listener);
  }

  get state(): ConnectionState {
    return this._state;
  }

  onDidChangeState(listener: (state: ConnectionState) => void): void {
    this.listeners.push(listener);
  }

  connect(address: string): void {
    this.disconnect();
    this.setState({ status: "connecting", address });

    const socket = new WebSocket(`ws://${address}/`);
    this.socket = socket;

    socket.on("open", () => {
      this.setState({ status: "connected", address });
    });

    socket.on("message", (raw) => {
      const event = parseStreamEvent(raw.toString());
      if (event) {
        for (const listener of this.streamListeners) listener(event);
      }
    });

    socket.on("error", (err) => {
      this.setState({ status: "error", address, message: err.message });
    });

    socket.on("close", () => {
      if (this._state.status !== "error") {
        this.setState({ status: "disconnected" });
      }
    });
  }

  disconnect(): void {
    this.socket?.removeAllListeners();
    this.socket?.close();
    this.socket = undefined;
    this.setState({ status: "disconnected" });
  }

  private setState(state: ConnectionState): void {
    this._state = state;
    for (const listener of this.listeners) {
      listener(state);
    }
  }
}
