export interface UserSummary {
  user_id: string;
  display_name: string;
  primary_role: 'client' | 'talent' | null;
  created_at: string;
  updated_at: string;
  verification_status: string;
  category_id?: string | null;
  city?: string | null;
  published?: boolean | null;
}
export interface Page<T> {
  items: T[];
  next_after: string | null;
  limit: number;
}
export interface BookingSummary {
  id: string;
  project_type: string;
  proposed_date: string;
  status: string;
}
export interface UserDetail {
  account: UserSummary;
  talent: {
    user_id: string;
    display_name: string;
    category_id: string;
    city: string;
    day_rate: number;
    published: boolean;
    available: boolean;
  }[];
  subscriptions: {
    entitlement: string;
    source: string;
    active: boolean;
    expires_at: string | null;
  }[];
  history: { id: number; action: string; occurred_at: string }[];
  history_note: string;
  security_activity: {
    events: { action: string; outcome: string; occurred_at: string }[];
    note: string;
  } | null;
}
export interface Verification {
  id: string;
  user_id: string;
  status: string;
  revision: number;
  submitted_at: string;
}
export interface VerificationDetail {
  request: Verification & { statement: string; consent_version: string };
  evidence: {
    id: string;
    portfolio_asset_id: string | null;
    portfolio_path: string;
    revision: number;
  }[];
  history: { id: string; decision: string; reason: string; created_at: string }[];
}
