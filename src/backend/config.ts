export type BackendConfig =
  | { mode: 'mock' }
  | { mode: 'supabase'; url: string; key: string }
  | { mode: 'invalid'; error: string };
export function backendConfig(env: { url?: string; key?: string; mode?: string }): BackendConfig {
  if (env.mode === 'mock') return { mode: 'mock' };
  if (env.mode && !['auto', 'supabase'].includes(env.mode))
    return { mode: 'invalid', error: 'Unknown backend mode. Use auto, mock or supabase.' };
  const url = env.url?.trim();
  const key = env.key?.trim();
  if (!url && !key && env.mode !== 'supabase') return { mode: 'mock' };
  if (!url || !key)
    return {
      mode: 'invalid',
      error:
        'Supabase configuration is incomplete. Set the project URL and publishable/anon key, or explicitly choose mock mode.',
    };
  try {
    const parsed = new URL(url);
    if (
      parsed.protocol !== 'https:' &&
      !(
        parsed.protocol === 'http:' &&
        ['localhost', '127.0.0.1', '10.0.2.2', '::1', '[::1]'].includes(parsed.hostname)
      )
    )
      throw new Error();
    if (parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error();
  } catch {
    return {
      mode: 'invalid',
      error: 'Use an HTTPS Supabase project URL (HTTP is allowed for a local development server).',
    };
  }
  let clientKey = key.startsWith('sb_publishable_');
  if (!clientKey)
    try {
      const encoded = key.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const payload = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')));
      clientKey = payload.role === 'anon';
    } catch {
      /* Not a client key. */
    }
  if (!clientKey)
    return {
      mode: 'invalid',
      error:
        'Use only a Supabase publishable or anon key. Secret/service-role keys are forbidden in the mobile application.',
    };
  return { mode: 'supabase', url, key };
}
