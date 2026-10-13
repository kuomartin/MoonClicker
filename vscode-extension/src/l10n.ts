import * as vscode from "vscode";
import { Text, errorMessage } from "./message";

export function localize(t: Text): string {
  return typeof t === "string" ? t : vscode.l10n.t(t.template, ...t.args);
}

export function errorText(err: unknown): string {
  return localize(errorMessage(err));
}
