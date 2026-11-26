const uuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
export function allowedBackend(method: string, path: string) {
  const routes: Record<string, RegExp> = {
    GET: new RegExp(
      `^(me|principals|sessions|audit|users|users/${uuid}|users/${uuid}/bookings|users/${uuid}/access|account-queues/(appeals|approvals|overdue)|users/verifications|users/verifications/${uuid})$`,
    ),
    POST: new RegExp(
      `^(sessions|invitations|invitations/activate|users/${uuid}/access|users/verifications/${uuid}/decision|users/verifications/${uuid}/evidence/${uuid}/preview)$`,
    ),
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
    if (
      !['after', 'before', 'limit', 'q', 'role', 'verification', 'state', 'from', 'until'].includes(
        key,
      ) ||
      value.length > (key === 'q' ? 80 : 50)
    )
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
export const PERMISSION_NAV: readonly {
  href: string;
  label: string;
  permission: string | null;
  module?: string;
}[] = [
  { href: '/dashboard', label: 'Overview', permission: null },
  { href: '/settings/admins', label: 'Administrators', permission: 'admins.manage' },
  { href: '/audit', label: 'Audit logs', permission: 'audit.read' },
  {
    href: '/account-queues',
    label: 'Account reviews',
    permission: 'accounts.read',
    module: 'user_management',
  },
  { href: '/users', label: 'Users', permission: 'users.read', module: 'user_management' },
  {
    href: '/users/verifications',
    label: 'Verifications',
    permission: 'users.verify',
    module: 'user_management',
  },
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
