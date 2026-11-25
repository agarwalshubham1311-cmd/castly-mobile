'use client';
import { useCallback, useState } from 'react';
import Link from 'next/link';
import { useAdmin } from '@/components/AdminShell';
import { useResource } from '@/components/UserManagement';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { api } from '@/lib/client';
import type { Verification, VerificationDetail } from '@/lib/users';
export default function Verifications() {
  const me = useAdmin(),
    allowed = !!me.modules?.user_management && !!me.permissions?.includes('users.verify');
  const [after, setAfter] = useState(''),
    [selected, setSelected] = useState(''),
    [decision, setDecision] = useState(''),
    [reason, setReason] = useState(''),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const queue = useResource<Verification[]>(
    'users/verifications?limit=25' + (after ? '&after=' + after : ''),
    allowed,
  );
  const detail = useResource<VerificationDetail>(
    'users/verifications/' + selected,
    allowed && !!selected,
  );
  const close = useCallback(() => {
    if (!busy) {
      setDecision('');
      setReason('');
    }
  }, [busy]);
  if (!allowed)
    return (
      <section className="card">
        <h1>Access restricted</h1>
        <p>Verification permission and approved reviewer access are required.</p>
      </section>
    );
  async function decide(e: React.FormEvent) {
    e.preventDefault();
    if (!detail.data) return;
    setBusy(true);
    setError('');
    try {
      await api('users/verifications/' + selected + '/decision', 'POST', {
        expected_revision: detail.data.request.revision,
        expected_status: detail.data.request.status,
        decision,
        reason,
      });
      setDecision('');
      setReason('');
      detail.retry();
      queue.retry();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Decision unavailable');
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <p className="eyebrow">Trust and verification</p>
      <h1>Verification review</h1>
      <p className="muted">
        Review consented profile and portfolio evidence. Verification is not an identity or
        background check. Sensitive decisions require recent MFA and an approved reviewer.
      </p>
      <section className="card">
        <div className="row">
          <h2 className="grow">Review queue</h2>
          <button onClick={queue.retry}>Refresh queue</button>
        </div>
        {queue.error ? (
          <p role="alert">
            {queue.error} <button onClick={queue.retry}>Retry queue</button>
          </p>
        ) : queue.loading ? (
          <p role="status">Loading verification queue…</p>
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Account</th>
                    <th>State</th>
                    <th>Submitted</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {queue.data?.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Link className="break-all" href={'/users/' + r.user_id}>
                          {r.user_id}
                        </Link>
                      </td>
                      <td>{r.status}</td>
                      <td>{new Date(r.submitted_at).toLocaleString()}</td>
                      <td>
                        <button
                          onClick={() => {
                            setSelected(r.id);
                            setError('');
                          }}
                        >
                          Review evidence
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!queue.data?.length && <p>No pending applications.</p>}
            <div className="row mt-4">
              <button disabled={!after} onClick={() => setAfter('')}>
                First page
              </button>
              <button
                disabled={queue.data?.length !== 25}
                onClick={() => setAfter(queue.data!.at(-1)!.id)}
              >
                Next page
              </button>
            </div>
          </>
        )}
      </section>
      {selected && (
        <section className="card">
          <h2>Application and history</h2>
          {detail.error ? (
            <p role="alert">
              {detail.error} <button onClick={detail.retry}>Retry evidence</button>
            </p>
          ) : detail.loading ? (
            <p role="status">Loading evidence…</p>
          ) : (
            detail.data && (
              <>
                <p>
                  State: {detail.data.request.status} · Revision {detail.data.request.revision}
                </p>
                <p>{detail.data.request.statement}</p>
                <p>Consent: {detail.data.request.consent_version}</p>
                <h3>Portfolio evidence references</h3>
                {detail.data.evidence.map((e) => (
                  <p className="break-all" key={e.id}>
                    {e.portfolio_path} · revision {e.revision}
                  </p>
                ))}
                {!detail.data.evidence.length && (
                  <p>No evidence is available under the current privacy and review rules.</p>
                )}
                <h3>Review history</h3>
                {detail.data.history.map((h) => (
                  <p key={h.id}>
                    {h.decision} · {h.reason} · {new Date(h.created_at).toLocaleString()}
                  </p>
                ))}
                {!detail.data.history.length && <p>No recorded decisions.</p>}
                <div className="row">
                  {(detail.data.request.status === 'PENDING'
                    ? ['UNDER_REVIEW', 'VERIFIED', 'REJECTED']
                    : detail.data.request.status === 'UNDER_REVIEW'
                      ? ['VERIFIED', 'REJECTED']
                      : detail.data.request.status === 'VERIFIED'
                        ? ['REVOKED']
                        : []
                  ).map((d) => (
                    <button
                      key={d}
                      onClick={() => {
                        setError('');
                        setDecision(d);
                      }}
                    >
                      {d === 'UNDER_REVIEW'
                        ? 'Begin review'
                        : d === 'VERIFIED'
                          ? 'Approve verification'
                          : d === 'REJECTED'
                            ? 'Reject verification'
                            : 'Revoke verification'}
                    </button>
                  ))}
                </div>
              </>
            )
          )}
        </section>
      )}
      {decision && (
        <ConfirmDialog title="Confirm verification decision" onClose={close}>
          <form onSubmit={decide}>
            <p>{decision}. This decision is audited against the current application revision.</p>
            {error && <p role="alert">{error}</p>}
            <label htmlFor="verification-reason">Decision reason</label>
            <textarea
              id="verification-reason"
              required
              minLength={3}
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
            <div className="row mt-4">
              <button type="button" onClick={close} disabled={busy}>
                Cancel
              </button>
              <button className="primary" disabled={busy}>
                {busy ? 'Saving…' : 'Confirm decision'}
              </button>
            </div>
          </form>
        </ConfirmDialog>
      )}
    </>
  );
}
