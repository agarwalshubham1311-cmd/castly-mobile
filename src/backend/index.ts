import { supabase } from './client';
import { createServices } from './services';
import { createApiServices, createApiTransport, selectBusinessMode } from './api';
export { configuration } from './client';
export const businessMode = selectBusinessMode(process.env.EXPO_PUBLIC_BUSINESS_API_MODE, __DEV__);
const fallback = businessMode === 'supabase';
const api = createApiServices(
  createApiTransport(
    process.env.EXPO_PUBLIC_API_URL || '',
    async (refresh) => {
      if (!supabase) return null;
      const result = refresh
        ? await supabase.auth.refreshSession()
        : await supabase.auth.getSession();
      if (result.error) throw result.error;
      return result.data.session?.access_token || null;
    },
    undefined,
    __DEV__,
  ),
);
const direct = supabase ? createServices(supabase) : null;
export const services = direct
  ? {
      ...(fallback ? direct : api),
      auth: direct.auth,
      media: direct.media,
      activity: { ...api.activity, ...(fallback ? direct.activity : {}) },
    }
  : null;
