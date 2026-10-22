import 'react-native-url-polyfill/auto';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { createClient, processLock } from '@supabase/supabase-js';
import { Platform } from 'react-native';
import { backendConfig } from './config';
import type { Database } from './types';
export const configuration = backendConfig({
  url: process.env.EXPO_PUBLIC_SUPABASE_URL,
  key: process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY,
  mode: process.env.EXPO_PUBLIC_BACKEND_MODE,
});
const fetchWithTimeout: typeof fetch = async (input, init) => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  const abort = () => controller.abort();
  if (init?.signal?.aborted) controller.abort();
  init?.signal?.addEventListener('abort', abort);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
    init?.signal?.removeEventListener('abort', abort);
  }
};
export const supabase =
  configuration.mode === 'supabase'
    ? createClient<Database>(configuration.url, configuration.key, {
        auth: {
          storage: AsyncStorage,
          storageKey: 'castly.auth.v1',
          persistSession: true,
          autoRefreshToken: true,
          detectSessionInUrl: false,
          flowType: 'pkce',
          ...(Platform.OS !== 'web' ? { lock: processLock } : {}),
        },
        global: { fetch: fetchWithTimeout },
      })
    : null;
