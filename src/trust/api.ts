import type { createApiTransport } from '../backend/api';
export type Verification = {
  id?: string;
  status:
    'NOT_SUBMITTED' | 'PENDING' | 'UNDER_REVIEW' | 'VERIFIED' | 'REJECTED' | 'EXPIRED' | 'REVOKED';
  statement?: string;
  expires_at?: string | null;
};
export type SafetyConfiguration = {
  features: {
    verification: boolean;
    reviews: boolean;
    reporting: boolean;
    trust_signals: boolean;
    disputes: boolean;
  };
  reviewer_scopes: string[];
};
export type BookingReview = {
  id: string;
  rating: number;
  body: string;
  editable_until: string;
  created_at: string;
};
export function createTrustServices(request: ReturnType<typeof createApiTransport>) {
  return {
    completion: (id: string) =>
      request<{
        booking: import('../backend/types').BookingRow;
        confirmed_by_me: boolean;
        confirmation_count: number;
      }>(`bookings/${encodeURIComponent(id)}/completion`),
    confirmCompletion: (id: string, actor: string) =>
      request(`bookings/${encodeURIComponent(id)}/completion`, 'POST', undefined, {
        expectedSubject: actor,
      }),
    ownReview: (id: string) =>
      request<BookingReview[]>(`bookings/${encodeURIComponent(id)}/reviews/mine`),
    submitReview: (id: string, rating: number, body: string, actor: string) =>
      request<BookingReview>(
        `bookings/${encodeURIComponent(id)}/reviews`,
        'POST',
        { rating, body },
        { expectedSubject: actor },
      ),
    editReview: (id: string, rating: number, body: string, actor: string) =>
      request<BookingReview>(
        `reviews/${encodeURIComponent(id)}`,
        'PATCH',
        { rating, body },
        { expectedSubject: actor },
      ),
    reviews: (id: string, offset = 0) =>
      request<{
        summary: { review_count: number; average_rating: number | null };
        reviews: BookingReview[];
      }>(`talents/${encodeURIComponent(id)}/reviews?offset=${offset}`),
    moderateReview: (id: string, hidden: boolean, reason: string, actor: string) =>
      request(
        `admin/reviews/${encodeURIComponent(id)}/moderation`,
        'POST',
        { hidden, reason },
        { expectedSubject: actor },
      ),
    configuration: () => request<SafetyConfiguration>('safety/configuration'),
    verification: () => request<Verification>('verification/me'),
    contact: () =>
      request<{ email_verified: boolean | null; phone_verified: boolean | null; status: string }>(
        'verification/contact',
      ),
    verificationHistory: () =>
      request<{ id: string; decision: string; reason: string; created_at: string }[]>(
        'verification/history',
      ),
    applyVerification: (statement: string, assets: string[], actor: string) =>
      request<Verification>(
        'verification',
        'POST',
        { statement, portfolio_asset_ids: assets, consent_version: 'profile-review-v1' },
        { expectedSubject: actor },
      ),
    verificationQueue: () =>
      request<(Verification & { id: string; user_id: string; display_name: string })[]>(
        'admin/verification',
      ),
    verificationEvidence: (id: string) =>
      request<{ id: string; portfolio_path: string }[]>(
        `verification/${encodeURIComponent(id)}/evidence`,
      ),
    verificationDecision: (id: string, decision: string, reason: string, actor: string) =>
      request<Verification>(
        `admin/verification/${encodeURIComponent(id)}/decision`,
        'POST',
        { decision, reason },
        { expectedSubject: actor },
      ),
  };
}
