import { createPlatformServices } from './platform.ts';
import { talentPayload } from './validation.ts';
import type { SupabaseClient } from '@supabase/supabase-js';
import type { Account, Database, Role, TalentDraft } from './types';
import { requireData } from './errors.ts';
/** Services accept a scoped client. Authorization remains enforced by SQL, never by these filters alone. */
export function createServices(db: SupabaseClient<Database>) {
  return {
    ...createPlatformServices(db),
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
      published: async (filters: { limit?: number; offset?: number } = {}) =>
        requireData(
          await db
            .from('talent_profiles')
            .select('*')
            .eq('published', true)
            .order('created_at', { ascending: false })
            .range(filters.offset || 0, (filters.offset || 0) + (filters.limit || 200) - 1),
        ),
      get: async (id: string) =>
        requireData(
          await db.from('talent_profiles').select('*').eq('id', id).eq('published', true).single(),
        )!,
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
      list: async (id: string, _published = false) =>
        requireData(
          await db.from('portfolio_assets').select('*').eq('talent_id', id).order('position'),
        ),
      published: async (offset = 0) =>
        requireData(
          await db
            .from('portfolio_assets')
            .select('*')
            .order('position')
            .order('id')
            .range(offset, offset + 199),
        ),
      add: async (talentId: string, path: string, label: string, position: number) =>
        requireData(
          await db
            .from('portfolio_assets')
            .insert({ talent_id: talentId, path, label, position })
            .select()
            .single(),
        ),
      edit: async (id: string, label: string, position: number) =>
        requireData(
          await db
            .from('portfolio_assets')
            .update({ label, position })
            .eq('id', id)
            .select()
            .single(),
        ),
      remove: async (id: string) => {
        requireData(await db.from('portfolio_assets').delete().eq('id', id));
      },
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
