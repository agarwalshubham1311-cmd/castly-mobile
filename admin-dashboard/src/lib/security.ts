const uuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
export function allowedBackend(method: string, path: string) {
  const routes: Record<string, RegExp> = {
    GET: /^(me|principals|sessions|audit)$/,
    POST: /^(sessions|invitations|invitations\/activate)$/,
    PATCH: new RegExp(`^principals/${uuid}$`),
    DELETE: new RegExp(`^sessions/${uuid}$`),
  };
  return !!routes[method]?.test(path);
}
export function safeOrigin(value: string, production: boolean) {
  const u = new URL(value);
  if (
    u.username ||
    u.password ||
    u.search ||
    u.hash ||
    u.pathname !== '/' ||
    !(
      u.protocol === 'https:' ||
      (!production &&
        ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname) &&
        u.protocol === 'http:')
    )
  )
    throw new Error('Exact trusted HTTPS origin required');
  return u.origin;
}
export function sameOrigin(origin: string | null, expected: string) {
  return origin !== null && origin === expected;
}
export function cleanQuery(query: URLSearchParams) {
  const result = new URLSearchParams();
  for (const [key, value] of query) {
    if (!['after', 'before', 'limit'].includes(key) || value.length > 50)
      throw new Error('Invalid query');
    result.append(key, value);
  }
  return result.toString();
}
export function browserFamily(ua: string) {
  return /firefox/i.test(ua)
    ? 'firefox'
    : /chrom/i.test(ua)
      ? 'chromium'
      : /safari/i.test(ua)
        ? 'safari'
        : 'other';
}
export const ROLES = [
  'OPERATIONS_ADMIN',
  'MODERATOR',
  'SUPPORT_AGENT',
  'FINANCE_ADMIN',
  'ANALYST',
] as const;
export const PERMISSION_NAV = [
  { href: '/dashboard', label: 'Overview', permission: null },
  { href: '/settings/admins', label: 'Administrators', permission: 'admins.manage' },
  { href: '/audit', label: 'Audit logs', permission: 'audit.read' },
];

// Keep cookie naming and transport validation on the same local-fixture decision.
export function adminCookiePolicy(env: Readonly<Record<string, string | undefined>>) {
  const production = env.NODE_ENV === 'production';
  const local =
    env.CASTLY_ADMIN_LOCAL_TEST === 'true' &&
    [env.ADMIN_ORIGIN, env.CASTLY_API_URL, env.SUPABASE_URL].every(
      (v) => v && /^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(v),
    );
  return {
    secure: production && !local,
    prefix: production && !local ? '__Host-' : '',
  };
}
