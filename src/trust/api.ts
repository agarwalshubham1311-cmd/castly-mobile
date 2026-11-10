import type { createApiTransport } from '../backend/api';
export type SafetyReport = {
  id: string;
  target_kind: string;
  target_id: string;
  category: string;
  description?: string;
  status: string;
  action_id?: string;
  decision?: string;
  reason?: string;
  target_snapshot?: Record<string, unknown>;
};
export type ModerationAction = {
  id: string;
  decision: string;
  reason: string;
  policy_version: string;
};
export type ModerationAppeal = {
  id: string;
  statement: string;
  status: string;
  resolution?: string;
};
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
        review_eligible: boolean;
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
    blocks: () => request<{ blocked_id: string; created_at: string }[]>('blocks'),
    block: (id: string, blocked: boolean, actor: string) =>
      request(`blocks/${encodeURIComponent(id)}`, blocked ? 'PUT' : 'DELETE', undefined, {
        expectedSubject: actor,
      }),
    report: (kind: string, id: string, category: string, description: string, actor: string) =>
      request<{ id: string; status: string }>(
        'reports',
        'POST',
        { target_kind: kind, target_id: id, category, description },
        { expectedSubject: actor },
      ),
    reports: () => request<SafetyReport[]>('reports'),
    actions: () => request<ModerationAction[]>('safety/actions'),
    moderationQueue: () => request<SafetyReport[]>('admin/moderation'),
    moderationDecision: (id: string, decision: string, reason: string, actor: string) =>
      request(
        `admin/moderation/${encodeURIComponent(id)}/decision`,
        'POST',
        { decision, reason },
        { expectedSubject: actor },
      ),
    appeal: (id: string, statement: string, actor: string) =>
      request(
        `safety/actions/${encodeURIComponent(id)}/appeal`,
        'POST',
        { statement },
        { expectedSubject: actor },
      ),
    appeals: () => request<ModerationAppeal[]>('safety/appeals'),
    appealQueue: () => request<ModerationAppeal[]>('admin/moderation/appeals'),
    resolveAppeal: (id: string, status: string, reason: string, actor: string) =>
      request(
        `admin/moderation/appeals/${encodeURIComponent(id)}/decision`,
        'POST',
        { status, reason },
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
