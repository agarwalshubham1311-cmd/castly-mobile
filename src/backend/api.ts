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
) {
  let base = baseUrl.trim().replace(/\/$/, '');
  if (base) {
    const url = new URL(base);
    if (
      (url.protocol !== 'https:' &&
        !(
          url.protocol === 'http:' &&
          (allowInsecureDevelopment ||
            ['localhost', '127.0.0.1', '10.0.2.2', '::1', '[::1]'].includes(url.hostname))
        )) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      throw new Error('Invalid Castly API URL');
  }
  return async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
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
      const token = await getToken(attempt === 1);
      if (attempt === 0) originalSubject = subject(token);
      else if (originalSubject !== subject(token))
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
        if (response.status === 401)
          throw new ApiError(401, 'Your session has expired. Sign in again.');
        if (!response.ok) {
          let detail = 'Request failed. Please retry.';
          try {
            const data = await response.json();
            if (typeof data.detail === 'string') detail = data.detail;
          } catch {
            /* Not a JSON API response. */
          }
          throw new ApiError(response.status, detail);
        }
        if (response.status === 204) return undefined as T;
        return (await response.json()) as T;
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
    account: {
      get: (id: string) => {
        void id;
        return request<Account>('account');
      },
      onboard: (role: Role, name: string) =>
        request<Account>('account/onboarding', 'POST', { selected_role: role, account_name: name }),
      rename: (id: string, name: string) => {
        void id;
        return request<Account>('account', 'PATCH', { display_name: name.trim() });
      },
      entitlements: () => request<Entitlement[]>('account/entitlements'),
    },
    talent: {
      published: (filters: Record<string, string | number | boolean> = {}) =>
        request<TalentProfile[]>(
          `talents?${new URLSearchParams(Object.entries(filters).map(([key, value]) => [key, String(value)]))}`,
        ),
      own: async (id: string) => {
        void id;
        try {
          return await request<TalentProfile | null>('talents/me');
        } catch (error) {
          if (error instanceof ApiError && error.status === 404) return null;
          throw error;
        }
      },
      create: (id: string, draft: TalentDraft) => {
        void id;
        return request<TalentProfile>('talents/me', 'POST', talentPayload(draft));
      },
      update: (id: string, draft: TalentDraft) => {
        void id;
        return request<TalentProfile>('talents/me', 'PUT', talentPayload(draft));
      },
      categories: () => request<CategoryRow[]>('categories'),
    },
    portfolio: {
      list: (id: string) =>
        request<PortfolioAsset[]>(`talents/${encodeURIComponent(id)}/portfolio`),
      published: () => request<PortfolioAsset[]>('portfolio'),
      add: (talentId: string, path: string, label: string, position: number) => {
        void talentId;
        return request<PortfolioAsset>('talents/me/portfolio', 'POST', { path, label, position });
      },
      edit: (id: string, label: string, position: number) =>
        request<PortfolioAsset>(`portfolio/${encodeURIComponent(id)}`, 'PATCH', {
          label,
          position,
        }),
      remove: (id: string) => request<void>(`portfolio/${encodeURIComponent(id)}`, 'DELETE'),
    },
    saved: {
      list: () => request<SavedRow[]>('favourites'),
      add: (userId: string, talentId: string) => {
        void userId;
        return request<void>(`favourites/${encodeURIComponent(talentId)}`, 'PUT');
      },
      remove: (userId: string, talentId: string) => {
        void userId;
        return request<void>(`favourites/${encodeURIComponent(talentId)}`, 'DELETE');
      },
    },
    activity: {
      bookings: (offset = 0) => request<BookingRow[]>(`bookings?limit=100&offset=${offset}`),
      booking: (id: string) => request<BookingRow>(`bookings/${encodeURIComponent(id)}`),
      createBooking: (body: BookingRequest) => request<BookingRow>('bookings', 'POST', body),
      transition: (id: string, decision: 'accept' | 'reject' | 'cancel') =>
        request<BookingRow>(`bookings/${encodeURIComponent(id)}/${decision}`, 'POST'),
      conversations: (offset = 0) =>
        request<ConversationRow[]>(`conversations?limit=100&offset=${offset}`),
      conversation: (id: string) =>
        request<ConversationRow>(`conversations/${encodeURIComponent(id)}`),
      openConversation: (bookingId: string) =>
        request<ConversationRow>('conversations', 'POST', { booking_id: bookingId }),
      messages: (id: string, before?: string) =>
        request<MessageRow[]>(
          `conversations/${encodeURIComponent(id)}/messages?limit=50${before ? '&before=' + encodeURIComponent(before) : ''}`,
        ),
      sendMessage: (id: string, body: string) =>
        request<MessageRow>(`conversations/${encodeURIComponent(id)}/messages`, 'POST', {
          body: body.trim(),
        }),
    },
  };
}

export function selectBusinessMode(
  value: string | undefined,
  development: boolean,
): 'spring' | 'supabase' {
  if (!value || value === 'spring') return 'spring';
  if (value === 'supabase' && development) return 'supabase';
  throw new Error(
    'Direct Supabase business access is a development-only fallback. Use spring for application builds.',
  );
}
