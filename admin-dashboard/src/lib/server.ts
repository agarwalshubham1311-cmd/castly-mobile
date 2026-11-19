import 'server-only';
import { cookies, headers as requestHeaders } from 'next/headers';
import { safeOrigin, browserFamily } from './security';
const cookiePrefix =
  process.env.NODE_ENV === 'production' && process.env.CASTLY_ADMIN_LOCAL_TEST !== 'true'
    ? '__Host-'
    : '';
export const ACCESS = cookiePrefix + 'castly_admin_access',
  REFRESH = cookiePrefix + 'castly_admin_refresh',
  SESSION = cookiePrefix + 'castly_admin_session';
export function configuration() {
  if (process.env.CASTLY_ADMIN_UI_ENABLED !== 'true')
    throw new Error('Administrator dashboard is disabled');
  const production = process.env.NODE_ENV === 'production';
  // Production requires HTTPS even for internal API origins. Local production-build tests opt into loopback explicitly.
  const local =
    process.env.CASTLY_ADMIN_LOCAL_TEST === 'true' &&
    [process.env.ADMIN_ORIGIN, process.env.CASTLY_API_URL, process.env.SUPABASE_URL].every(
      (v) => v && /^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(v),
    );
  return {
    origin: safeOrigin(process.env.ADMIN_ORIGIN ?? '', production && !local),
    api: safeOrigin(process.env.CASTLY_API_URL ?? '', production && !local),
    auth: safeOrigin(process.env.SUPABASE_URL ?? '', production && !local),
    key: process.env.SUPABASE_PUBLISHABLE_KEY ?? '',
    secure: production && !local,
  };
}
export async function authRequest(path: string, method: string, body?: unknown, access?: string) {
  const c = configuration();
  if (!c.key.startsWith('sb_publishable_') && !c.key.startsWith('eyJ'))
    throw new Error('Public Supabase key required');
  // Legacy anon JWT must explicitly carry role=anon. No service-role keys accepted.
  if (c.key.startsWith('eyJ')) {
    const payload = JSON.parse(Buffer.from(c.key.split('.')[1], 'base64url').toString());
    if (payload.role !== 'anon') throw new Error('Anon key required');
  }
  const r = await fetch(c.auth + '/auth/v1/' + path, {
    method,
    headers: {
      apikey: c.key,
      'Content-Type': 'application/json',
      ...(access ? { Authorization: 'Bearer ' + access } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
    redirect: 'error',
    signal: AbortSignal.timeout(5000),
  });
  if (!r.ok) throw new Error('Identity verification failed');
  const text = await boundedResponse(r);
  return text ? JSON.parse(text) : {};
}
export async function boundedResponse(r: Response) {
  if (!r.body) return '';
  const reader = r.body.getReader();
  let size = 0;
  const chunks: Uint8Array[] = [];
  try {
    for (;;) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.length;
      if (size > 262144) throw new Error('Response exceeds bound');
      chunks.push(part.value);
    }
  } finally {
    await reader.cancel();
  }
  return Buffer.concat(chunks).toString('utf8');
}
export async function writeTokens(value: { access_token?: string; refresh_token?: string }) {
  if (!value.access_token || !value.refresh_token) throw new Error('Missing session');
  const c = configuration(),
    jar = await cookies();
  const options = {
    httpOnly: true,
    secure: c.secure,
    sameSite: 'strict' as const,
    path: '/',
    maxAge: 3600,
  };
  jar.set(ACCESS, value.access_token, options);
  jar.set(REFRESH, value.refresh_token, options);
}
export async function clearTokens() {
  const jar = await cookies();
  for (const key of [ACCESS, REFRESH, SESSION])
    jar.set(key, '', {
      httpOnly: true,
      secure: configuration().secure,
      sameSite: 'strict',
      path: '/',
      maxAge: 0,
    });
}
export async function apiRequest(path: string, method: string, body?: unknown) {
  const c = configuration(),
    jar = await cookies();
  const access = jar.get(ACCESS)?.value;
  if (!access) return new Response('{}', { status: 401 });
  return fetch(c.api + '/api/v1/admin/' + path, {
    method,
    headers: {
      Authorization: 'Bearer ' + access,
      'User-Agent': browserFamily((await requestHeaders()).get('user-agent') ?? ''),
      'Content-Type': 'application/json',
      ...(jar.get(SESSION) ? { 'X-Castly-Admin-Session': jar.get(SESSION)!.value } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
    redirect: 'error',
    signal: AbortSignal.timeout(6000),
  });
}
// Supplemental process-local auth throttling; Supabase and operator edge quotas remain required.
const attempts = new Map<string, { at: number; count: number }>();
export function loginLimit() {
  const now = Date.now(),
    key = 'auth';
  const old = attempts.get(key);
  const row = !old || now - old.at > 60000 ? { at: now, count: 0 } : old;
  row.count++;
  attempts.set(key, row);
  if (row.count > 30) throw new Error('Identity attempts limited');
}

export async function boundedInput(request: Request, maximum: number) {
  if (!request.body) return '';
  const reader = request.body.getReader();
  let size = 0;
  const chunks: Uint8Array[] = [];
  try {
    for (;;) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.length;
      if (size > maximum) throw new Error('Request exceeds bound');
      chunks.push(part.value);
    }
  } finally {
    await reader.cancel();
  }
  return Buffer.concat(chunks).toString('utf8');
}
