import WebSocket from "ws";
import { fromBinary } from "@bufbuild/protobuf";
import { ProtocolVersion, StreamEventSchema, type StreamEvent } from "./generated/workbench_stream_event_pb";
import { msg, type Text } from "./message";

export type { StreamEvent };

/** 這個擴充套件說的 Workbench 協定版本，見 proto 的 `ProtocolVersion`。 */
export const PROTOCOL_VERSION: number = ProtocolVersion.CURRENT;

/** App 因協定版本不同而關閉串流時用的 close code。 */
const POLICY_VIOLATION = 1008;

export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting"; address: string }
  | { status: "connected"; address: string }
  | { status: "error"; address: string; message: Text };

/**
 * 解析一筆原始 WebSocket 二進位訊息成 [StreamEvent]。形狀來自
 * proto/workbench_stream_event.proto，跟裝置端 WorkbenchServer.kt 是同一份 schema
 * 產生的型別，不是這裡手動猜出來的。不是合法 protobuf 就回傳 `undefined`，呼叫端
 * 直接忽略——不是這個連線該處理的東西，不代表連線壞了。
 */
export function parseStreamEvent(raw: Uint8Array): StreamEvent | undefined {
  try {
    return fromBinary(StreamEventSchema, raw);
  } catch {
    return undefined;
  }
}

/** `ws` 的 `message` 事件依 fragmentation 可能給 Buffer、Buffer[] 或 ArrayBuffer。 */
function toBytes(raw: WebSocket.RawData): Uint8Array {
  if (raw instanceof ArrayBuffer) return new Uint8Array(raw);
  return Buffer.isBuffer(raw) ? raw : Buffer.concat(raw);
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

  connect(address: string, token?: string): void {
    this.disconnect();
    this.setState({ status: "connecting", address });

    const query = new URLSearchParams({ protocol: String(PROTOCOL_VERSION) });
    if (token) query.set("token", token);
    const wsUrl = `ws://${address}/?${query}`;
    const socket = new WebSocket(wsUrl);
    this.socket = socket;

    socket.on("open", () => {
      this.setState({ status: "connected", address });
    });

    socket.on("message", (raw, isBinary) => {
      // 文字 frame 是 #57 的連線 echo，不是 StreamEvent——protobuf binary decode
      // 對任意位元組不保證會丟例外，讓文字 frame 也去解碼可能得到一筆假造的事件。
      if (!isBinary) return;
      const event = parseStreamEvent(toBytes(raw));
      if (event) {
        for (const listener of this.streamListeners) listener(event);
      }
    });

    socket.on("error", (err) => {
      this.setState({ status: "error", address, message: err.message });
    });

    socket.on("close", (code, reason) => {
      if (code === POLICY_VIOLATION) {
        this.setState({
          status: "error",
          address,
          message: msg("The app and the extension use different protocol versions. Update the older one ({0})", reason.toString()),
        });
        return;
      }
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
