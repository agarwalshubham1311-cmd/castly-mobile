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
export function createTrustServices(request: ReturnType<typeof createApiTransport>) {
  return {
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
