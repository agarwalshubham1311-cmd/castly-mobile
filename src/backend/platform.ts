import type { SupabaseClient } from '@supabase/supabase-js';
import type { Database } from './types';
import { requireData } from './errors.ts';
/** Supabase is exclusively Auth and Storage in Spring business mode. */
export function createPlatformServices(db: SupabaseClient<Database>) {
  return {
    auth: {
      login: async (email: string, password: string) => {
        const result = await db.auth.signInWithPassword({ email: email.trim(), password });
        if (result.error) throw result.error;
        return result.data;
      },
      signup: async (email: string, password: string, name: string, redirectTo: string) =>
        requireData(
          await db.auth.signUp({
            email: email.trim(),
            password,
            options: { data: { display_name: name.trim() }, emailRedirectTo: redirectTo },
          }),
        ),
      exchangeCode: async (code: string) => {
        const result = await db.auth.exchangeCodeForSession(code);
        if (result.error) throw result.error;
        return result.data;
      },
      logout: async () => {
        const { error } = await db.auth.signOut({ scope: 'local' });
        if (error) throw error;
      },
    },
    media: {
      signedUrl: async (path: string) =>
        requireData(await db.storage.from('portfolio').createSignedUrl(path, 60))!.signedUrl,
      upload: async (path: string, body: ArrayBuffer, contentType: string) =>
        requireData(
          await db.storage.from('portfolio').upload(path, body, { contentType, upsert: false }),
        ),
      remove: async (path: string) =>
        requireData(await db.storage.from('portfolio').remove([path])),
    },
  };
}
