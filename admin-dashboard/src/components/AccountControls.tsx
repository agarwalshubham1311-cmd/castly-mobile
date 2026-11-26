'use client';
import { useState } from 'react';
import Link from 'next/link';
import { api, ApiError } from '@/lib/client';
import { useAdmin } from './AdminShell';
import { ConfirmDialog } from './ConfirmDialog';
import { useResource } from './UserManagement';
export const REASONS = [
  'SPAM',
  'MISLEADING_PROFILE',
  'IMPERSONATION',
  'VERIFICATION_FAILURE',
  'BOOKING_MISCONDUCT',
  'HARASSMENT',
  'FRAUD',
  'PROHIBITED_CONTENT',
  'SAFETY_VIOLATION',
  'REPEATED_VIOLATIONS',
  'ACCOUNT_COMPROMISE',
  'OTHER',
] as const;
const CAPABILITIES = [
  'discovery',
  'booking_create',
  'booking_accept',
  'messaging',
  'profile',
  'subscription_purchase',
] as const;
type Appeal = {
  id: string;
  user_id?: string;
  decision_id: string;
  version: number;
  status: string;
  statement?: string;
  evidence_ref?: string;
  resolution?: string;
  due_at: string;
  overdue: boolean;
  reopen_evidence: string | null;
};
type Proposal = {
  id: string;
  user_id?: string;
  action: string;
  state?: string;
  proposer_id: string;
  base_version: number;
  explanation?: string;
  reason_code?: string;
};
type Access = {
  state: string;
  version: number;
  explanation?: string;
  until_at?: string;
  reason_code?: string;
  investigation?: string;
  denied?: string[];
  decisions: { id: string; state: string; explanation: string; notified_at: string }[];
  appeals: Appeal[];
  proposals: Proposal[];
  escalations: { id: string; notes: string; created_at: string }[];
  notes: { decision_id: string; notes: string; evidence_ref: string }[];
};
export function AccountControls({ id }: { id: string }) {
  const me = useAdmin(),
    can = (p: string) => !!me.permissions?.includes(p),
    resource = useResource<Access>('users/' + id + '/access', can('accounts.read'));
  const [state, setState] = useState('TEMPORARILY_SUSPENDED'),
    [hours, setHours] = useState('24'),
    [reason, setReason] = useState<string>('ACCOUNT_COMPROMISE'),
    [explanation, setExplanation] = useState(''),
    [evidence, setEvidence] = useState(''),
    [notes, setNotes] = useState(''),
    [denied, setDenied] = useState<string[]>(['discovery']);
  const [pending, setPending] = useState<{
      operation: string;
      payload: Record<string, unknown>;
      key: string;
    } | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const d = resource.data,
    superAdmin = me.roles.includes('SUPER_ADMIN');
  function prepare(operation: string, payload: Record<string, unknown>) {
    setError('');
    setPending({ operation, payload, key: crypto.randomUUID() });
  }
  async function execute() {
    if (!pending || busy) return;
    setBusy(true);
    try {
      await api('users/' + id + '/access', 'POST', {
        request_key: pending.key,
        operation: pending.operation,
        payload: pending.payload,
      });
      setPending(null);
      resource.retry();
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 409
          ? 'State changed or request conflicts. Refresh and review the latest decision before retrying.'
          : e instanceof Error
            ? e.message
            : 'Request failed',
      );
    } finally {
      setBusy(false);
    }
  }
  const fields = () => ({
    version: d?.version,
    state,
    duration_hours: Number(hours),
    reason_code: reason,
    explanation,
    evidence_ref: evidence,
    notes,
    denied,
  });
  if (!can('accounts.read')) return null;
  if (resource.error)
    return (
      <section className="card">
        <h2>Account access</h2>
        <p role="alert">{resource.error}</p>
        <button onClick={resource.retry}>Refresh account access</button>
      </section>
    );
  if (!d) return <p role="status">Loading account access…</p>;
  return (
    <section className="card">
      <h2>Account access · {d.state.replaceAll('_', ' ')}</h2>
      <p>{d.explanation}</p>
      <p>
        Version {d.version} · Investigation {d.investigation ?? 'Not opened'}
      </p>
      {d.until_at && <p>Expiry: {new Date(d.until_at).toLocaleString()}</p>}
      <p className="muted">
        Existing bookings and financial obligations remain intact. ACCOUNT_COMPROMISE is protective,
        not a misconduct finding. No automatic punishment is applied.
      </p>
      {error && <p role="alert">{error}</p>}
      <button onClick={resource.retry}>Refresh account access</button>
      {can('accounts.enforce') && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            prepare(
              state === 'PERMANENTLY_SUSPENDED' || d.state === 'PERMANENTLY_SUSPENDED'
                ? 'PROPOSE'
                : state === 'ACTIVE'
                  ? 'REINSTATE'
                  : 'ENFORCE',
              fields(),
            );
          }}
        >
          <div className="grid">
            <label>
              Enforcement state
              <select value={state} onChange={(e) => setState(e.target.value)}>
                <option value="ACTIVE">Reinstate account</option>
                <option value="RESTRICTED">Feature restrictions</option>
                <option value="TEMPORARILY_SUSPENDED">Temporary suspension</option>
                {superAdmin && (
                  <option value="PERMANENTLY_SUSPENDED">Propose permanent suspension</option>
                )}
              </select>
            </label>
            <label>
              Duration
              <select value={hours} onChange={(e) => setHours(e.target.value)}>
                {[
                  1,
                  24,
                  72,
                  168,
                  ...(me.roles.some((r) => ['OPERATIONS_ADMIN', 'SUPER_ADMIN'].includes(r))
                    ? [720]
                    : []),
                ].map((h) => (
                  <option key={h} value={h}>
                    {h} hours
                  </option>
                ))}
              </select>
            </label>
            <label>
              Reason
              <select value={reason} onChange={(e) => setReason(e.target.value)}>
                {REASONS.map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </select>
            </label>
          </div>
          {state === 'RESTRICTED' && (
            <fieldset>
              <legend>Restricted capabilities</legend>
              {CAPABILITIES.map((c) => (
                <label key={c}>
                  <input
                    type="checkbox"
                    checked={denied.includes(c)}
                    onChange={(e) =>
                      setDenied(e.target.checked ? [...denied, c] : denied.filter((x) => x !== c))
                    }
                  />
                  {c.replaceAll('_', ' ')}
                </label>
              ))}
            </fieldset>
          )}
          <label>
            User-visible explanation
            <textarea
              required
              minLength={10}
              maxLength={1000}
              value={explanation}
              onChange={(e) => setExplanation(e.target.value)}
            />
          </label>
          <label>
            Evidence reference
            <input
              required
              minLength={3}
              maxLength={500}
              value={evidence}
              onChange={(e) => setEvidence(e.target.value)}
            />
          </label>
          <label>
            Confidential investigation notes
            <textarea maxLength={4000} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>
          <button disabled={busy || (!superAdmin && d.state === 'PERMANENTLY_SUSPENDED')}>
            Review enforcement decision
          </button>
          <p>
            Recent MFA is required. Duration limits apply to the entire active episode, including
            extensions.
          </p>
          <button
            type="button"
            onClick={() =>
              prepare('INVESTIGATE', {
                version: d.version,
                investigation: d.investigation === 'CLOSED' ? 'OPEN' : 'CLOSED',
              })
            }
          >
            Set investigation {d.investigation === 'CLOSED' ? 'OPEN' : 'CLOSED'}
          </button>
        </form>
      )}
      {can('accounts.escalate') && !can('accounts.enforce') && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            prepare('ESCALATE', { notes });
          }}
        >
          <label>
            Confidential escalation
            <textarea
              required
              minLength={10}
              maxLength={4000}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </label>
          <button>Escalate for review</button>
        </form>
      )}
      <h3>Confidential escalations</h3>
      {d.escalations.map((e) => (
        <p className="break-all" key={e.id}>
          {new Date(e.created_at).toLocaleString()} · {e.notes}
        </p>
      ))}
      <h3>Approval proposals</h3>
      {d.proposals.map((p) => (
        <div key={p.id}>
          <p>
            {p.action} · {p.state} · Reference {p.id}
          </p>
          <p>
            {p.explanation} {p.reason_code}
          </p>
          {p.state === 'PENDING' && can('accounts.permanent') && p.proposer_id !== me.user_id && (
            <button onClick={() => prepare('APPROVE', { proposal_id: p.id })}>
              Review independent approval
            </button>
          )}
        </div>
      ))}
      <h3>Appeals</h3>
      {d.appeals.map((a) => (
        <div key={a.id} className="card">
          <p>
            {a.status} · Due {new Date(a.due_at).toLocaleString()}
            {a.overdue ? ' · Overdue: escalate review' : ''}
          </p>
          <p className="break-all">{a.statement}</p>
          <p className="break-all">Evidence: {a.evidence_ref}</p>
          <p>{a.resolution}</p>
          {can('accounts.appeals') && (
            <>
              {a.status === 'SUBMITTED' && (
                <button
                  onClick={() =>
                    prepare('APPEAL_REVIEW', { appeal_id: a.id, appeal_version: a.version })
                  }
                >
                  Begin independent review
                </button>
              )}
              {a.status === 'UNDER_REVIEW' && (
                <>
                  <label>
                    Public appeal resolution
                    <textarea
                      minLength={10}
                      maxLength={1000}
                      value={explanation}
                      onChange={(e) => setExplanation(e.target.value)}
                    />
                  </label>
                  {['UPHELD', 'MODIFIED', 'OVERTURNED'].map((outcome) => (
                    <button
                      key={outcome}
                      onClick={() =>
                        prepare('APPEAL_DECIDE', {
                          ...fields(),
                          appeal_id: a.id,
                          appeal_version: a.version,
                          outcome,
                          resolution: explanation,
                        })
                      }
                    >
                      Review {outcome.toLowerCase()}
                    </button>
                  ))}
                </>
              )}
              {a.reopen_evidence && (
                <>
                  <p>New evidence: {a.reopen_evidence}</p>
                  <button
                    onClick={() =>
                      prepare('APPEAL_REOPEN', {
                        appeal_id: a.id,
                        appeal_version: a.version,
                        significant: true,
                      })
                    }
                  >
                    Confirm significant evidence and reopen
                  </button>
                </>
              )}
            </>
          )}
        </div>
      ))}
      <h3>Immutable decision history</h3>
      {d.decisions.map((x) => (
        <div key={x.id}>
          <p>
            {x.state} · {new Date(x.notified_at).toLocaleString()}
          </p>
          <p>{x.explanation}</p>
        </div>
      ))}
      {d.notes.length > 0 && (
        <>
          <h3>Confidential investigation history</h3>
          {d.notes.map((x) => (
            <p className="break-all" key={x.decision_id}>
              {x.evidence_ref} · {x.notes}
            </p>
          ))}
        </>
      )}
      {pending && (
        <ConfirmDialog
          title="Confirm account action"
          onClose={() => {
            if (!busy) setPending(null);
          }}
        >
          <p>
            {pending.operation.replaceAll('_', ' ')} · Target reference {id}
          </p>
          <p>
            {String(
              pending.payload.explanation ??
                'Review the current account and independent approval requirements before confirming.',
            )}
          </p>
          <p>
            Authorization, MFA, version and duration limits will be rechecked by the server. No real
            notifications are sent.
          </p>
          <button disabled={busy} onClick={() => void execute()}>
            {busy ? 'Saving…' : 'Confirm reviewed action'}
          </button>
        </ConfirmDialog>
      )}
    </section>
  );
}
export function AccountQueues() {
  const me = useAdmin(),
    [kind, setKind] = useState('appeals'),
    [after, setAfter] = useState('');
  const permission = kind === 'approvals' ? 'accounts.permanent' : 'accounts.appeals',
    allowed = !!me.permissions?.includes(permission);
  const r = useResource<(Appeal & Proposal)[]>(
    'account-queues/' + kind + '?limit=25' + (after ? '&after=' + after : ''),
    allowed,
  );
  return (
    <>
      <h1>Account review queues</h1>
      <label>
        Review queue
        <select
          value={kind}
          onChange={(e) => {
            setKind(e.target.value);
            setAfter('');
          }}
        >
          <option value="appeals">All pending appeals</option>
          <option value="overdue">Overdue escalation queue</option>
          <option value="approvals">Independent permanent approvals</option>
        </select>
      </label>
      {!allowed ? (
        <p>Permission required for this queue.</p>
      ) : (
        <>
          <p role="alert">{r.error}</p>
          {r.data?.map((x) => (
            <section className="card" key={x.id}>
              <Link href={'/users/' + x.user_id}>Review account {x.user_id}</Link>
              <p>
                {x.status ?? x.action}
                {x.overdue ? ' · Overdue: escalate review' : ''}
              </p>
              {x.due_at && <p>Review target {new Date(x.due_at).toLocaleString()}</p>}
            </section>
          ))}
          {r.data?.length === 0 && <p>No pending reviews.</p>}
          <button onClick={() => setAfter('')}>First page</button>
          <button disabled={r.data?.length !== 25} onClick={() => setAfter(r.data!.at(-1)!.id)}>
            Next reviews
          </button>
        </>
      )}
    </>
  );
}
