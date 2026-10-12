/**
 * 給使用者看的訊息：英文原文 [template] 加參數，佔位語法 `{0}` 與 `vscode.l10n.t` 相同。
 * 純邏輯模組不依賴 vscode、只產生 Message，由 VS Code 層的 `l10n.ts` 翻譯，單元測試照樣能跑。
 */
export interface Message {
  readonly template: string;
  readonly args: readonly (string | number)[];
}

/** 字串是裝置端回傳或系統錯誤的原文，不翻譯。 */
export type Text = Message | string;

export function msg(template: string, ...args: (string | number)[]): Message {
  return { template, args };
}

export function formatText(t: Text): string {
  if (typeof t === "string") return t;
  return t.template.replace(/\{(\d+)\}/g, (all, i: string) => (i in t.args ? String(t.args[Number(i)]) : all));
}

/** `message` 是格式化後的英文，[text] 保留原本的 Message 給 VS Code 層翻譯。 */
export class MessageError extends Error {
  constructor(readonly text: Text) {
    super(formatText(text));
  }
}

export function errorMessage(err: unknown): Text {
  if (err instanceof MessageError) return err.text;
  return err instanceof Error ? err.message : String(err);
}
