export type MirrorConnectionState =
  | { status: "disconnected" }
  | { status: "connecting"; address: string; displayId: number }
  | { status: "connected"; address: string; displayId: number }
  | { status: "error"; address: string; displayId: number; message: string };

/**
 * 從 `multipart/x-mixed-replace` 回應的 `Content-Type` header 取出 boundary token；
 * 拿不到就代表這不是 `/mirror/{displayId}` 該回的格式。
 */
function boundaryOf(contentType: string | null): string | undefined {
  return contentType?.match(/boundary=([^;]+)/i)?.[1];
}

/**
 * `/mirror/{displayId}` 那份 multipart 回應的 parser——直接對應 `WorkbenchServer.kt`
 * 該 route 寫出來的確切格式（`--boundary\r\nContent-Type: image/jpeg\r\nContent-Length:
 * <n>\r\n\r\n<n bytes><\r\n>` 逐幀重複），不是通用的 MJPEG parser，不處理這份格式以外的變形。
 */
export class MultipartFrameParser {
  private buffer = Buffer.alloc(0);
  private readonly marker: Buffer;

  constructor(boundary: string) {
    this.marker = Buffer.from(`--${boundary}\r\n`);
  }

  /** 餵入新收到的位元組，回傳這次餵入後新湊齊的完整 JPEG frame（可能 0 個到多個）。 */
  push(chunk: Uint8Array): Buffer[] {
    this.buffer = Buffer.concat([this.buffer, Buffer.from(chunk)]);
    const frames: Buffer[] = [];
    for (;;) {
      const before = this.buffer.length;
      const frame = this.tryExtractOne();
      if (frame) frames.push(frame);
      if (this.buffer.length === before) break; // 沒有進展：剩下的都是還沒收完整的資料
    }
    return frames;
  }

  private tryExtractOne(): Buffer | undefined {
    if (this.buffer.length < this.marker.length) return undefined;
    if (!this.buffer.subarray(0, this.marker.length).equals(this.marker)) {
      // 開頭不是預期的 boundary——格式跟假設對不上，整份丟棄避免卡死在死資料上。
      this.buffer = Buffer.alloc(0);
      return undefined;
    }
    const headerEnd = this.buffer.indexOf("\r\n\r\n", this.marker.length);
    if (headerEnd === -1) return undefined; // header 還沒收完整

    const header = this.buffer.subarray(this.marker.length, headerEnd).toString("utf8");
    const length = Number(header.match(/Content-Length:\s*(\d+)/i)?.[1]);
    const bodyStart = headerEnd + 4;
    if (!Number.isInteger(length)) {
      // 沒有合法的 Content-Length：跳過這個 header，往下一個 boundary 找。
      this.buffer = this.buffer.subarray(bodyStart);
      return undefined;
    }

    const bodyEnd = bodyStart + length;
    if (this.buffer.length < bodyEnd + 2) return undefined; // body（含結尾 \r\n）還沒收滿

    const frame = Buffer.from(this.buffer.subarray(bodyStart, bodyEnd));
    this.buffer = this.buffer.subarray(bodyEnd + 2);
    return frame;
  }
}

/**
 * Realtime mirror 連線的純邏輯（見 #77）。走 HTTP GET `/mirror/{displayId}` 的 multipart
 * 串流，跟 [WorkbenchConnection] 那條 log/data.set 的 WebSocket 是完全分開的傳輸與狀態——
 * 兩者的 connect/disconnect 互不影響，各自獨立管理一份連線。
 *
 * 沿用 [WorkbenchConnection] 的 [MirrorConnectionState] 四態模式（disconnected/
 * connecting/connected/error），不另外定義「stale」狀態：連線在 TCP 層面仍活著、只是很久
 * 沒收到新 frame（畫面靜止不變、或裝置端卡住），跟連線真的斷掉是不同的事，兩者混進同一個
 * 狀態機只會製造假警報。這一層只如實回報連線本身死活；多久沒收到新 frame 交給呼叫端量測
 * [onDidReceiveFrame] 的到達時間自己判斷、自己決定要不要顯示成畫面卡住。
 */
export class MirrorConnection {
  private abortController: AbortController | undefined;
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

  start(address: string, displayId: number): void {
    this.stop();
    const controller = new AbortController();
    this.abortController = controller;
    this.setState({ status: "connecting", address, displayId });
    void this.run(address, displayId, controller);
  }

  /** 面板關閉或使用者主動停止都走這條路——中止還在進行中的 fetch，讓底層串流確實停止讀取。 */
  stop(): void {
    this.abortController?.abort();
    this.abortController = undefined;
    if (this._state.status !== "disconnected") {
      this.setState({ status: "disconnected" });
    }
  }

  private async run(address: string, displayId: number, controller: AbortController): Promise<void> {
    let response: Response;
    try {
      response = await fetch(`http://${address}/mirror/${displayId}`, { signal: controller.signal });
    } catch (err) {
      if (controller.signal.aborted) return; // 使用者主動停止，不是連線失敗
      this.setState({ status: "error", address, displayId, message: (err as Error).message });
      return;
    }

    if (!response.ok || !response.body) {
      if (controller.signal.aborted) return;
      this.setState({
        status: "error",
        address,
        displayId,
        message: `HTTP ${response.status}${response.status === 404 ? "（displayId 沒有活著的鏡像來源）" : ""}`,
      });
      return;
    }
    const boundary = boundaryOf(response.headers.get("content-type"));
    if (!boundary) {
      this.setState({ status: "error", address, displayId, message: "回應不是預期的 multipart 格式" });
      return;
    }

    this.setState({ status: "connected", address, displayId });
    const parser = new MultipartFrameParser(boundary);
    const reader = response.body.getReader();
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const frame of parser.push(value)) {
          for (const listener of this.frameListeners) listener(frame);
        }
      }
      // 串流正常結束（裝置端主動關閉，例如 WorkbenchServer.stop() 或鏡像畫面關閉）——
      // 不是使用者主動停止就不該悄悄留在 connected，讓面板能顯示明確的斷線狀態。
      if (!controller.signal.aborted) {
        this.setState({ status: "disconnected" });
      }
    } catch (err) {
      if (controller.signal.aborted) return;
      this.setState({ status: "error", address, displayId, message: (err as Error).message });
    }
  }

  private setState(state: MirrorConnectionState): void {
    this._state = state;
    for (const listener of this.stateListeners) listener(state);
  }
}
