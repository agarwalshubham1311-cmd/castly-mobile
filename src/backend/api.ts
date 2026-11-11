import { createTrustServices } from '../trust/api.ts';
import type { BillingConfiguration, BillingResult } from '../subscriptions/billing';
import type { Plan, Membership, PromotionClaim, SubscriptionOrder } from '../subscriptions/catalog';
import type {
  Account,
  TalentProfile,
  TalentDraft,
  PortfolioAsset,
  CategoryRow,
  SavedRow,
  Entitlement,
  BookingRow,
  ConversationRow,
  MessageRow,
  Role,
  NotificationRow,
  NotificationStats,
} from './types.ts';
import { talentPayload } from './validation.ts';
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = 'ApiError';
  }
}
export function createApiTransport(
  baseUrl: string,
  getToken: (refresh?: boolean) => Promise<string | null>,
  fetcher: typeof fetch = fetch,
  allowInsecureDevelopment = false,
  expired: (subject: string | null) => void = () => {},
) {
  let base = baseUrl.trim().replace(/\/$/, '');
  if (base) {
    let url: URL;
    try {
      url = new URL(base);
    } catch {
      throw new Error('Invalid Castly API URL. Use an origin without /api/v1.');
    }
    if (
      (url.protocol !== 'https:' &&
        !(
          url.protocol === 'http:' &&
          (allowInsecureDevelopment ||
            ['localhost', '127.0.0.1', '10.0.2.2', '::1', '[::1]'].includes(url.hostname))
        )) ||
      url.pathname !== '/' ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      throw new Error('Invalid Castly API URL');
  }
  return async function request<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    options: { authenticated?: boolean; expectedSubject?: string } = {},
  ): Promise<T> {
    if (!base) throw new Error('Castly API URL is not configured. Set EXPO_PUBLIC_API_URL.');
    let originalSubject: string | null = null;
    const subject = (token: string | null) => {
      try {
        if (!token) return null;
        const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        return String(
          JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '='))).sub || '',
        );
      } catch {
        return null;
      }
    };
    for (let attempt = 0; attempt < 2; attempt++) {
      let token: string | null;
      try {
        token = options.authenticated === false ? null : await getToken(attempt === 1);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          expired(originalSubject);
          throw error;
        }
        throw new Error(
          'Cannot reach Castly. Check your connection and retry. Changes have not been confirmed.',
        );
      }
      if (attempt === 0) {
        originalSubject = subject(token);
        if (options.expectedSubject && originalSubject !== options.expectedSubject)
          throw new ApiError(401, 'Your account changed. Retry from the current account.');
      } else if (originalSubject !== subject(token))
        throw new ApiError(401, 'Your account changed. Retry from the current account.');
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      try {
        const response = await fetcher(`${base}/api/v1/${path}`, {
          method,
          headers: {
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          },
          ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
          signal: controller.signal,
        });
        if (response.status === 401 && token && attempt === 0) continue;
        if (response.status === 401) {
          if (options.authenticated !== false) expired(originalSubject);
          throw new ApiError(401, 'Your session has expired. Sign in again.');
        }
        if (!response.ok) {
          let detail = 'Request failed. Please retry.';
          try {
            const data = await response.json();
            if (typeof data.detail === 'string') detail = data.detail;
            if (typeof data.reference === 'string' && /^[0-9a-f-]{36}$/i.test(data.reference))
              detail += ` Share reference ${data.reference} with support if the problem persists.`;
          } catch {
            /* Not a JSON API response. */
          }
          throw new ApiError(response.status, detail);
        }
        const result = response.status === 204 ? undefined : await response.json();
        if (options.authenticated !== false && originalSubject !== subject(await getToken(false)))
          throw new ApiError(401, 'Your account changed. Retry from the current account.');
        return result as T;
      } catch (error) {
        if (error instanceof ApiError) throw error;
        throw new Error(
          'Cannot reach Castly. Check your connection and retry. Changes have not been confirmed.',
        );
      } finally {
        clearTimeout(timeout);
      }
    }
    throw new ApiError(401, 'Your session has expired. Sign in again.');
  };
}
export type DiscoveryQuery = {
  query?: string;
  category?: string;
  city?: string;
  minRate?: number;
  maxRate?: number;
  available?: boolean;
  limit?: number;
  offset?: number;
};
export type BookingRequest = {
  talent_id: string;
  project_type: string;
  location: string;
  requirements: string;
  proposed_date: string;
  indicative_budget: number;
};
export function createApiServices(request: ReturnType<typeof createApiTransport>) {
  return {
    trust: createTrustServices(request),
    subscriptions: {
      reviewQueue: (offset = 0) =>
        request<(PromotionClaim & { user_id: string })[]>(
          `admin/subscriptions/promotions?limit=25&offset=${offset}`,
        ),
      review: (id: string, decision: 'approve' | 'reject', reason: string, actor: string) =>
        request<PromotionClaim>(
          `admin/subscriptions/promotions/${encodeURIComponent(id)}/${decision}`,
          'POST',
          { reason },
          { expectedSubject: actor },
        ),
      billingConfiguration: () =>
        request<BillingConfiguration>('subscriptions/google-play/configuration'),
      plans: () =>
        request<Plan[]>('subscriptions/plans', 'GET', undefined, { authenticated: false }),
      current: () => request<Membership>('subscriptions/me'),
      history: () => request<SubscriptionOrder[]>('subscriptions/history'),
      claims: () => request<PromotionClaim[]>('subscriptions/promotions/me'),
      claim: (postUrl: string, actor: string) =>
        request<PromotionClaim>(
          'subscriptions/promotions/claims',
          'POST',
          { post_url: postUrl },
          { expectedSubject: actor },
        ),
      verify: (token: string, actor: string) =>
        request<BillingResult>(
          'subscriptions/google-play/verify',
          'POST',
          { purchase_token: token },
          { expectedSubject: actor },
        ),
      restore: (token: string, actor: string) =>
        request<BillingResult>(
          'subscriptions/google-play/restore',
          'POST',
          { purchase_token: token },
          { expectedSubject: actor },
        ),
    },
    account: {
      get: (id: string) => {
        return request<Account>('account', 'GET', undefined, { expectedSubject: id });
      },
      onboard: (role: Role, name: string, actor?: string) =>
        request<Account>(
          'account/onboarding',
          'POST',
          { selected_role: role, account_name: name },
          { expectedSubject: actor },
        ),
      rename: (id: string, name: string) => {
        return request<Account>(
          'account',
          'PATCH',
          { display_name: name.trim() },
          { expectedSubject: id },
        );
      },
      entitlements: () => request<Entitlement[]>('account/entitlements'),
    },
    talent: {
      published: (filters: DiscoveryQuery = {}) =>
        request<TalentProfile[]>(
          `talents?${new URLSearchParams(
            Object.entries(filters)
              .filter(([, value]) => value !== undefined)
              .map(([key, value]) => [key, String(value)]),
          )}`,
          'GET',
          undefined,
          { authenticated: false },
        ),
      get: (id: string) =>
        request<TalentProfile>(`talents/${encodeURIComponent(id)}`, 'GET', undefined, {
          authenticated: false,
        }),
      own: async (id: string) => {
        try {
          return await request<TalentProfile | null>('talents/me', 'GET', undefined, {
            expectedSubject: id,
          });
        } catch (error) {
          if (error instanceof ApiError && error.status === 404) return null;
          throw error;
        }
      },
      create: (id: string, draft: TalentDraft) => {
        return request<TalentProfile>('talents/me', 'POST', talentPayload(draft), {
          expectedSubject: id,
        });
      },
      update: (id: string, draft: TalentDraft) => {
        return request<TalentProfile>('talents/me', 'PUT', talentPayload(draft), {
          expectedSubject: id,
        });
      },
      categories: () =>
        request<CategoryRow[]>('categories', 'GET', undefined, { authenticated: false }),
    },
    portfolio: {
      list: (id: string, published = false) =>
        request<PortfolioAsset[]>(`talents/${encodeURIComponent(id)}/portfolio`, 'GET', undefined, {
          authenticated: !published,
        }),
      published: (offset = 0) =>
        request<PortfolioAsset[]>(`portfolio?limit=200&offset=${offset}`, 'GET', undefined, {
          authenticated: false,
        }),
      add: (talentId: string, path: string, label: string, position: number) => {
        return request<PortfolioAsset>(
          'talents/me/portfolio',
          'POST',
          { path, label, position },
          { expectedSubject: talentId },
        );
      },
      edit: (id: string, label: string, position: number, actor?: string) =>
        request<PortfolioAsset>(
          `portfolio/${encodeURIComponent(id)}`,
          'PATCH',
          {
            label,
            position,
          },
          { expectedSubject: actor },
        ),
      remove: (id: string, actor?: string) =>
        request<void>(`portfolio/${encodeURIComponent(id)}`, 'DELETE', undefined, {
          expectedSubject: actor,
        }),
    },
    saved: {
      list: () => request<SavedRow[]>('favourites'),
      add: (userId: string, talentId: string) => {
        return request<void>(`favourites/${encodeURIComponent(talentId)}`, 'PUT', undefined, {
          expectedSubject: userId,
        });
      },
      remove: (userId: string, talentId: string) => {
        return request<void>(`favourites/${encodeURIComponent(talentId)}`, 'DELETE', undefined, {
          expectedSubject: userId,
        });
      },
    },
    notifications: {
      stats: () => request<NotificationStats>('notifications/unread-count'),
      list: (before?: string) =>
        request<NotificationRow[]>(
          `notifications?limit=50${before ? '&before=' + encodeURIComponent(before) : ''}`,
        ),
      read: (id: string, actor?: string) =>
        request<void>(`notifications/${encodeURIComponent(id)}/read`, 'POST', undefined, {
          expectedSubject: actor,
        }),
      readConversation: (id: string, actor?: string, through?: string) =>
        request<void>(
          `conversations/${encodeURIComponent(id)}/notifications/read${through ? '?through=' + encodeURIComponent(through) : ''}`,
          'POST',
          undefined,
          { expectedSubject: actor },
        ),
    },
    activity: {
      bookings: (offset = 0) => request<BookingRow[]>(`bookings?limit=100&offset=${offset}`),
      booking: (id: string) => request<BookingRow>(`bookings/${encodeURIComponent(id)}`),
      createBooking: (body: BookingRequest, actor?: string) =>
        request<BookingRow>(
          'bookings',
          'POST',
          {
            talent_id: body.talent_id,
            project_type: body.project_type,
            location: body.location,
            requirements: body.requirements,
            proposed_date: body.proposed_date,
            indicative_budget: body.indicative_budget,
          },
          { expectedSubject: actor },
        ),
      transition: (id: string, decision: 'accept' | 'reject' | 'cancel', actor?: string) =>
        request<BookingRow>(`bookings/${encodeURIComponent(id)}/${decision}`, 'POST', undefined, {
          expectedSubject: actor,
        }),
      conversations: (offset = 0) =>
        request<ConversationRow[]>(`conversations?limit=100&offset=${offset}`),
      conversation: (id: string) =>
        request<ConversationRow>(`conversations/${encodeURIComponent(id)}`),
      openConversation: (bookingId: string, actor?: string) =>
        request<ConversationRow>(
          'conversations',
          'POST',
          { booking_id: bookingId },
          { expectedSubject: actor },
        ),
      messages: (id: string, before?: string) =>
        request<MessageRow[]>(
          `conversations/${encodeURIComponent(id)}/messages?limit=50${before ? '&before=' + encodeURIComponent(before) : ''}`,
        ),
      sendMessage: (id: string, body: string, actor?: string) =>
        request<MessageRow>(
          `conversations/${encodeURIComponent(id)}/messages`,
          'POST',
          {
            body: body.trim(),
          },
          { expectedSubject: actor },
        ),
    },
  };
}

export function selectBusinessMode(
  value: string | undefined,
  development: boolean,
  isolatedDevDatabase = false,
): 'spring' | 'supabase' {
  if (!value || value === 'spring') return 'spring';
  if (value === 'supabase' && development && isolatedDevDatabase) return 'supabase';
  throw new Error(
    'Direct Supabase business access is a development-only fallback for an explicitly isolated DB (EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK=true). Use spring for application builds.',
  );
}
