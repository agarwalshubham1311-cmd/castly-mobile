import { talentPayload } from './validation.ts';
import type { SupabaseClient } from '@supabase/supabase-js';
import type { Account, Database, Role, TalentDraft } from './types';
import { requireData } from './errors.ts';
/** Services accept a scoped client. Authorization remains enforced by SQL, never by these filters alone. */
export function createServices(db: SupabaseClient<Database>) {
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
    account: {
      get: async (id: string): Promise<Account> =>
        requireData(await db.from('users').select('*').eq('id', id).single())!,
      onboard: async (role: Role, name: string): Promise<Account> =>
        requireData(
          await db.rpc('complete_onboarding', { selected_role: role, account_name: name }).single(),
        )!,
      rename: async (id: string, name: string) =>
        requireData(
          await db
            .from('users')
            .update({ display_name: name.trim() })
            .eq('id', id)
            .select()
            .single(),
        ),
      entitlements: async () => requireData(await db.from('subscription_entitlements').select('*')),
    },
    talent: {
      published: async () =>
        requireData(
          await db
            .from('talent_profiles')
            .select('*')
            .eq('published', true)
            .order('created_at', { ascending: false })
            .limit(200),
        ),
      own: async (id: string) =>
        requireData(await db.from('talent_profiles').select('*').eq('id', id).maybeSingle()),
      create: async (id: string, draft: TalentDraft) =>
        requireData(
          await db
            .from('talent_profiles')
            .insert({ ...talentPayload(draft), id })
            .select()
            .single(),
        ),
      update: async (id: string, draft: TalentDraft) =>
        requireData(
          await db
            .from('talent_profiles')
            .update(talentPayload(draft))
            .eq('id', id)
            .select()
            .single(),
        ),
      categories: async () => requireData(await db.from('categories').select('*').order('name')),
    },
    portfolio: {
      list: async (id: string) =>
        requireData(
          await db.from('portfolio_assets').select('*').eq('talent_id', id).order('position'),
        ),
      published: async () =>
        requireData(await db.from('portfolio_assets').select('*').order('position').limit(1000)),
      add: async (talentId: string, path: string, label: string, position: number) =>
        requireData(
          await db
            .from('portfolio_assets')
            .insert({ talent_id: talentId, path, label, position })
            .select()
            .single(),
        ),
      remove: async (id: string) => {
        requireData(await db.from('portfolio_assets').delete().eq('id', id));
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
    saved: {
      list: async () => requireData(await db.from('saved_talent').select('*')),
      add: async (userId: string, talentId: string) => {
        requireData(await db.from('saved_talent').insert({ user_id: userId, talent_id: talentId }));
      },
      remove: async (userId: string, talentId: string) => {
        requireData(
          await db.from('saved_talent').delete().eq('user_id', userId).eq('talent_id', talentId),
        );
      },
    },
    // Milestone 1 exposes participant-scoped reads only. No live send, status transition or billing API.
    activity: {
      bookings: async () =>
        requireData(
          await db.from('bookings').select('*').order('created_at', { ascending: false }),
        ),
      conversations: async () =>
        requireData(
          await db.from('conversations').select('*').order('created_at', { ascending: false }),
        ),
      messages: async (conversationId: string) =>
        requireData(
          await db
            .from('messages')
            .select('*')
            .eq('conversation_id', conversationId)
            .order('created_at'),
        ),
    },
  };
}
