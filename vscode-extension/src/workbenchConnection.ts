import WebSocket from "ws";

export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting"; address: string }
  | { status: "connected"; address: string }
  | { status: "error"; address: string; message: string };

/**
 * WebSocket 連線的純邏輯（不依賴 vscode API），單元測試不需要跑在 VS Code extension host 裡。
 * `extension.ts` 只負責把 [state] 的變化接到狀態列與指令上。
 */
export class WorkbenchConnection {
  private socket: WebSocket | undefined;
  private _state: ConnectionState = { status: "disconnected" };
  private listeners: Array<(state: ConnectionState) => void> = [];

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
