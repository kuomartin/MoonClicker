export async function pairWithPin(address: string, pin: string): Promise<string> {
  const response = await fetch(`http://${address}/pair`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pin }),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `配對失敗（HTTP ${response.status}）`);
  }

  const data = (await response.json()) as { token: string };
  return data.token;
}

export function authHeaders(token?: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}
