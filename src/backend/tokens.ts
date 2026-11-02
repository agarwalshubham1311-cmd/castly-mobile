import type { SupabaseClient } from '@supabase/supabase-js';
import { ApiError } from './api.ts';
/** Share refresh-token rotation across concurrent API 401s; Supabase remains the session owner. */
export function createSessionTokenProvider(
  auth: Pick<SupabaseClient['auth'], 'getSession' | 'refreshSession'>,
) {
  let refreshing: ReturnType<typeof auth.refreshSession> | undefined;
  return async (refresh = false) => {
    const result = refresh
      ? await (refreshing ||= auth.refreshSession().finally(() => {
          refreshing = undefined;
        }))
      : await auth.getSession();
    if (result.error) {
      if ([400, 401, 403].includes(result.error.status || 0))
        throw new ApiError(401, 'Your session has expired. Sign in again.');
      throw result.error;
    }
    return result.data.session?.access_token || null;
  };
}
