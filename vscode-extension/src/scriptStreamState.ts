import type { DataEvent } from "./generated/workbench_stream_event_pb";

export type ScriptData = Record<string, unknown>;

/**
 * 依腳本累積串流送來的 data。裝置每條連線、每支腳本先送一筆 reset 的完整快照，之後只送差異，
 * 這裡把差異套回完整的 map。
 */
export class ScriptDataStore {
  private readonly data = new Map<string, ScriptData>();

  /** 套用一筆 [DataEvent]，回傳這支腳本套用後的完整 data。 */
  apply(event: DataEvent): ScriptData {
    const current = event.reset ? {} : { ...(this.data.get(event.scriptId) ?? {}) };
    for (const key of event.removed) delete current[key];
    Object.assign(current, event.values ?? {});
    this.data.set(event.scriptId, current);
    return current;
  }

  get(scriptId: string): ScriptData | undefined {
    return this.data.get(scriptId);
  }

  /** 以腳本 id 為鍵的全部 data。 */
  all(): Record<string, ScriptData> {
    return Object.fromEntries(this.data);
  }

  /** 換連線時呼叫：新連線會重送每支腳本的快照。 */
  clear(): void {
    this.data.clear();
  }
}
