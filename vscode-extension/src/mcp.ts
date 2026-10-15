/**
 * App 內建 MCP server 的 client 設定（`mcpServers` 格式，Claude Code、Claude Desktop、Cursor 等通用）。
 * 驗證沿用 Workbench token。
 */
export function mcpServerConfig(address: string, token: string | undefined): string {
  const server: Record<string, unknown> = { type: "http", url: `http://${address}/mcp` };
  if (token) server.headers = { Authorization: `Bearer ${token}` };
  return JSON.stringify({ mcpServers: { moonclicker: server } }, null, 2);
}
