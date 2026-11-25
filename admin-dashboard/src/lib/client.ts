export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api/backend/' + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  const value = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new ApiError(response.status, value.detail ?? 'Administrative request failed');
  return value;
}
export async function auth<T>(action: string, body: unknown = {}): Promise<T> {
  const response = await fetch('/api/auth/' + action, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    cache: 'no-store',
  });
  const value = await response.json();
  if (!response.ok) throw new ApiError(response.status, value.detail ?? 'Authentication failed');
  return value;
}
export interface Principal {
  user_id: string;
  roles: string[];
  state: string;
  version: number;
  permissions?: string[];
  expires_at?: string;
  modules?: { user_management: boolean };
}
export interface AdminSession {
  expired: boolean;
  id: string;
  created_at: string;
  expires_at: string;
  revoked_at: string | null;
  agent_family: string;
}
