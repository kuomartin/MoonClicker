import * as vscode from "vscode";
import {
  deleteEntry,
  getTree,
  mkdir,
  readFile,
  renameEntry,
  ScriptHttpError,
  ScriptTreeEntry,
  writeFile,
} from "./scriptSync";
import { FileChangeEvent, FileChangeEvent_Kind } from "./generated/workbench_stream_event_pb";

/**
 * `moonclicker://<address>/<scriptId>/<relative/path>` 直接對著裝置上的 Script Folder
 * 讀寫，取代整包 zip 的 pull/push（見 docs/plans/vscode-fsprovider-plan.md）。address 放
 * 在 authority 而不是外部狀態——一個 provider 實例天生就能服務多個裝置／多個已開啟的
 * virtual workspace folder，不用綁死「目前連線」這個全域假設。
 */
export function scriptUri(address: string, scriptId: string, relativePath = ""): vscode.Uri {
  const cleanPath = relativePath.split("/").filter(Boolean).join("/");
  return vscode.Uri.from({
    scheme: SCHEME,
    authority: address,
    path: `/${scriptId}${cleanPath ? "/" + cleanPath : ""}`,
  });
}

export const SCHEME = "moonclicker";

interface ParsedUri {
  address: string;
  scriptId: string;
  /** 相對於 script 資料夾根目錄的路徑；根目錄本身是空字串。 */
  relativePath: string;
}

export function parseUri(uri: vscode.Uri): ParsedUri {
  const segments = uri.path.split("/").filter(Boolean);
  const [scriptId, ...rest] = segments;
  return { address: uri.authority, scriptId: scriptId ?? "", relativePath: rest.join("/") };
}

function treeKey(address: string, scriptId: string): string {
  return `${address}/${scriptId}`;
}

export class MoonclickerFileSystemProvider implements vscode.FileSystemProvider {
  private readonly _onDidChangeFile = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  readonly onDidChangeFile = this._onDidChangeFile.event;

  /** scriptId 的整棵樹快取（`ScriptTreeEntry[]`），readDirectory/stat 靠它避免每次都打網路。 */
  private readonly trees = new Map<string, Promise<ScriptTreeEntry[]>>();

  constructor(private readonly getToken: (address: string) => string | undefined) {}

  /** 連線／重新整理時主動丟掉快取，下次存取重新從裝置拿一份最新的。 */
  invalidate(address: string, scriptId: string): void {
    this.trees.delete(treeKey(address, scriptId));
  }

  /**
   * 裝置透過 WebSocket 推播的 `file_change`（見 WorkbenchConnection）——目前只有
   * self-write 會觸發（外部改動偵測已放棄，見 plan E3），主要效果是多個 VS Code
   * client 連著同一顆腳本時彼此同步。
   */
  applyRemoteChange(address: string, event: FileChangeEvent): void {
    const uri = scriptUri(address, event.scriptId, event.path);
    this.invalidate(address, event.scriptId);
    const kind =
      event.kind === FileChangeEvent_Kind.DELETED
        ? vscode.FileChangeType.Deleted
        : event.kind === FileChangeEvent_Kind.CREATED
          ? vscode.FileChangeType.Created
          : vscode.FileChangeType.Changed;
    this._onDidChangeFile.fire([{ type: kind, uri }]);
  }

  private async ensureTree(address: string, scriptId: string): Promise<ScriptTreeEntry[]> {
    let pending = this.trees.get(treeKey(address, scriptId));
    if (!pending) {
      pending = getTree(address, scriptId, this.getToken(address));
      this.trees.set(treeKey(address, scriptId), pending);
      // 拿失敗就不要卡住快取——下次存取重新打一次網路，而不是永遠回同一個 rejected promise。
      pending.catch(() => this.trees.delete(treeKey(address, scriptId)));
    }
    return pending;
  }

  watch(): vscode.Disposable {
    // 沒有裝置端 push 外部改動（FileObserver 已放棄）；self-write 一律透過
    // `applyRemoteChange` 主動送 change event，不需要靠 VS Code 呼叫這個方法來訂閱特定
    // uri——所有已知的改動來源都已經被涵蓋。
    return new vscode.Disposable(() => {});
  }

  async stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    const { address, scriptId, relativePath } = parseUri(uri);
    if (relativePath === "") {
      return { type: vscode.FileType.Directory, ctime: 0, mtime: 0, size: 0 };
    }
    const tree = await this.ensureTree(address, scriptId);
    const entry = tree.find((e) => e.path === relativePath);
    if (!entry) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }
    return {
      type: entry.isDirectory ? vscode.FileType.Directory : vscode.FileType.File,
      ctime: entry.mtimeMs,
      mtime: entry.mtimeMs,
      size: entry.size,
    };
  }

  async readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    const { address, scriptId, relativePath } = parseUri(uri);
    const tree = await this.ensureTree(address, scriptId);
    const prefix = relativePath === "" ? "" : relativePath + "/";
    const children = new Map<string, vscode.FileType>();
    for (const entry of tree) {
      if (!entry.path.startsWith(prefix) || entry.path === relativePath) continue;
      const rest = entry.path.slice(prefix.length);
      const [name, ...more] = rest.split("/");
      if (more.length === 0) {
        children.set(name, entry.isDirectory ? vscode.FileType.Directory : vscode.FileType.File);
      } else if (!children.has(name)) {
        // 中繼資料夾不一定會被列在 tree 裡自己的一筆——裝置端 `walkTopDown()` 會，但這裡
        // 不假設；有更深的後代就代表這一層至少是個資料夾。
        children.set(name, vscode.FileType.Directory);
      }
    }
    return Array.from(children.entries());
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const { address, scriptId, relativePath } = parseUri(uri);
    try {
      return await readFile(address, scriptId, relativePath, this.getToken(address));
    } catch (err) {
      throw toFileSystemError(uri, err);
    }
  }

  async writeFile(
    uri: vscode.Uri,
    content: Uint8Array,
    options: { create: boolean; overwrite: boolean },
  ): Promise<void> {
    const { address, scriptId, relativePath } = parseUri(uri);
    const existed = await this.exists(address, scriptId, relativePath);
    if (!existed && !options.create) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }
    if (existed && !options.overwrite) {
      throw vscode.FileSystemError.FileExists(uri);
    }
    try {
      await writeFile(address, scriptId, relativePath, content, this.getToken(address));
    } catch (err) {
      throw toFileSystemError(uri, err);
    }
    this.invalidate(address, scriptId);
    this._onDidChangeFile.fire([
      { type: existed ? vscode.FileChangeType.Changed : vscode.FileChangeType.Created, uri },
    ]);
  }

  async delete(uri: vscode.Uri): Promise<void> {
    const { address, scriptId, relativePath } = parseUri(uri);
    try {
      await deleteEntry(address, scriptId, relativePath, this.getToken(address));
    } catch (err) {
      throw toFileSystemError(uri, err);
    }
    this.invalidate(address, scriptId);
    this._onDidChangeFile.fire([{ type: vscode.FileChangeType.Deleted, uri }]);
  }

  async rename(
    oldUri: vscode.Uri,
    newUri: vscode.Uri,
    options: { overwrite: boolean },
  ): Promise<void> {
    const from = parseUri(oldUri);
    const to = parseUri(newUri);
    try {
      await renameEntry(
        from.address,
        from.scriptId,
        from.relativePath,
        to.relativePath,
        options.overwrite,
        this.getToken(from.address),
      );
    } catch (err) {
      throw toFileSystemError(newUri, err);
    }
    this.invalidate(from.address, from.scriptId);
    this._onDidChangeFile.fire([
      { type: vscode.FileChangeType.Deleted, uri: oldUri },
      { type: vscode.FileChangeType.Created, uri: newUri },
    ]);
  }

  async createDirectory(uri: vscode.Uri): Promise<void> {
    const { address, scriptId, relativePath } = parseUri(uri);
    try {
      await mkdir(address, scriptId, relativePath, this.getToken(address));
    } catch (err) {
      throw toFileSystemError(uri, err);
    }
    this.invalidate(address, scriptId);
    this._onDidChangeFile.fire([{ type: vscode.FileChangeType.Created, uri }]);
  }

  private async exists(address: string, scriptId: string, relativePath: string): Promise<boolean> {
    if (relativePath === "") return true;
    const tree = await this.ensureTree(address, scriptId);
    return tree.some((e) => e.path === relativePath);
  }
}

function toFileSystemError(uri: vscode.Uri, err: unknown): vscode.FileSystemError {
  if (err instanceof ScriptHttpError) {
    if (err.status === 404) return vscode.FileSystemError.FileNotFound(uri);
    if (err.status === 409) return vscode.FileSystemError.FileExists(uri);
  }
  return vscode.FileSystemError.Unavailable((err as Error).message ?? String(err));
}
