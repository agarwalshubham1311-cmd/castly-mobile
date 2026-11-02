import { supabase } from './client';
import { composeServices } from './composition';
import { createPlatformServices } from './platform';
import { sessionExpired } from './sessionEvents';
import { createServices } from './services';
import { createSessionTokenProvider } from './tokens';
import { createApiServices, createApiTransport, selectBusinessMode } from './api';
export { configuration } from './client';
export const businessMode = selectBusinessMode(
  process.env.EXPO_PUBLIC_BUSINESS_API_MODE,
  __DEV__,
  process.env.EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK === 'true',
);
const api = createApiServices(
  createApiTransport(
    process.env.EXPO_PUBLIC_API_URL || '',
    supabase ? createSessionTokenProvider(supabase.auth) : async () => null,
    undefined,
    __DEV__,
    sessionExpired,
  ),
);
export const services = supabase
  ? composeServices(businessMode, api, createPlatformServices(supabase), () =>
      createServices(supabase!),
    )
  : null;
