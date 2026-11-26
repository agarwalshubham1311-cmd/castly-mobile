export type EnforcementState =
  'ACTIVE' | 'RESTRICTED' | 'TEMPORARILY_SUSPENDED' | 'PERMANENTLY_SUSPENDED';
export type Capability =
  | 'discovery'
  | 'booking_create'
  | 'booking_accept'
  | 'messaging'
  | 'profile'
  | 'subscription_purchase';
export interface AccountDecision {
  id: string;
  state: EnforcementState;
  explanation: string;
  reason_code: string;
  notified_at: string;
  until_at: string | null;
}
export interface Appeal {
  id: string;
  decision_id: string;
  version: number;
  status: 'SUBMITTED' | 'UNDER_REVIEW' | 'UPHELD' | 'MODIFIED' | 'OVERTURNED' | 'WITHDRAWN';
  statement: string;
  evidence_ref: string;
  resolution: string | null;
  due_at: string;
  overdue: boolean;
  reopen_evidence: string | null;
}
export interface AccessStatus {
  available: boolean;
  state: EnforcementState;
  version?: number;
  explanation?: string;
  until_at?: string | null;
  denied?: Capability[];
  decisions?: AccountDecision[];
  appeals?: Appeal[];
}
export interface AppealCommand {
  request_key: string;
  operation: 'APPEAL_SUBMIT' | 'APPEAL_WITHDRAW' | 'APPEAL_REOPEN_REQUEST';
  payload: Record<string, string | number>;
}
