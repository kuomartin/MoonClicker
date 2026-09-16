import { WebSocket } from "ws";

export type MirrorConnectionState =
  | { status: "disconnected" }
  | { status: "connecting"; address: string; displayId: number }
  | { status: "connected"; address: string; displayId: number }
  | { status: "error"; address: string; displayId: number; message: string };

/**
 * Realtime mirror 連線的純邏輯（見 #77）。走 HTTP GET `/mirror/h264/{displayId}` 的 WebSocket 
 * H.264 串流，跟 [WorkbenchConnection] 那條 log/data.set 的 WebSocket 是完全分開的傳輸與狀態。
 */
export class MirrorConnection {
  private ws: WebSocket | undefined;
  private _state: MirrorConnectionState = { status: "disconnected" };
  private stateListeners: Array<(state: MirrorConnectionState) => void> = [];
  private frameListeners: Array<(frame: Buffer) => void> = [];

  get state(): MirrorConnectionState {
    return this._state;
  }

  onDidChangeState(listener: (state: MirrorConnectionState) => void): void {
    this.stateListeners.push(listener);
  }

  onDidReceiveFrame(listener: (frame: Buffer) => void): void {
    this.frameListeners.push(listener);
  }

  start(address: string, displayId: number, token?: string): void {
    this.stop();
    this.setState({ status: "connecting", address, displayId });

    // WorkbenchServer 目前 export address = ip:port
    const wsUrl = token
      ? `ws://${address}/mirror/h264/${displayId}?token=${encodeURIComponent(token)}`
      : `ws://${address}/mirror/h264/${displayId}`;
    const ws = new WebSocket(wsUrl);
    this.ws = ws;

    ws.on("open", () => {
      if (this.ws !== ws) return;
      this.setState({ status: "connected", address, displayId });
    });

    ws.on("message", (data, isBinary) => {
      if (this.ws !== ws) return;
      if (isBinary && Buffer.isBuffer(data)) {
        for (const listener of this.frameListeners) listener(data);
      }
    });

    ws.on("close", () => {
      if (this.ws !== ws) return;
      this.setState({ status: "disconnected" });
      this.ws = undefined;
    });

    ws.on("error", (err) => {
      if (this.ws !== ws) return;
      this.setState({ status: "error", address, displayId, message: err.message });
      this.ws = undefined;
    });
  }

  /** 面板關閉或使用者主動停止都走這條路——關閉 WebSocket 連線。 */
  stop(): void {
    if (this.ws) {
      this.ws.close();
      this.ws = undefined;
    }
    if (this._state.status !== "disconnected") {
      this.setState({ status: "disconnected" });
    }
  }


  private setState(state: MirrorConnectionState): void {
    this._state = state;
    for (const listener of this.stateListeners) listener(state);
  }
}
