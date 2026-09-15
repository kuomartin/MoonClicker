export type StalenessState = "fresh" | "stale";

/**
 * 判斷「連線還活著、但很久沒收到新畫面」的狀態（見 #77 驗收條件：裝置休眠、WiFi 斷線、
 * workbench service 停止都要有明確視覺指示，不能停在最後一幀讓人誤以為還活著）。
 *
 * 這件事不是 [MirrorConnection] 能回報的——它的 docstring 已經講清楚：連線在 TCP 層面
 * 沒斷、只是很久沒收到新 frame，跟連線真的斷掉是不同的事，判斷「多久算卡住」要交給
 * 呼叫端量測 frame 到達的時間間隔自己決定。`/mirror/{displayId}` 這條路由也沒有心跳幀
 * （見 WorkbenchServer.kt），裝置端畫面沒更新時單純不送新 byte，所以只能用逾時去猜。
 */
export class FrameStalenessTracker {
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly staleAfterMs: number,
    private readonly onChange: (state: StalenessState) => void,
  ) {}

  /** 連線進入 connected：從這一刻開始等第一幀，逾時一樣算 stale——遲遲沒有第一幀也是卡住。 */
  armFromConnect(): void {
    this.rearm();
  }

  /** 收到一幀新畫面：回報 fresh，並重新起算逾時。 */
  noteFrame(): void {
    this.onChange("fresh");
    this.rearm();
  }

  /** 連線離開 connected（connecting/disconnected/error）：staleness 這個問題本身沒意義，停用計時器。 */
  disarm(): void {
    this.clearTimer();
  }

  private rearm(): void {
    this.clearTimer();
    this.timer = setTimeout(() => this.onChange("stale"), this.staleAfterMs);
  }

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }
}
