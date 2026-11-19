'use client';
import { useEffect, useState, useCallback } from 'react';
import { useAdmin } from '@/components/AdminShell';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { api, type AdminSession } from '@/lib/client';
export default function Overview() {
  const me = useAdmin(),
    [sessions, setSessions] = useState<AdminSession[]>([]),
    [error, setError] = useState(''),
    [target, setTarget] = useState<string | null>(null),
    [reason, setReason] = useState(''),
    [busy, setBusy] = useState(false);
  const close = useCallback(() => {
    setTarget(null);
    setReason('');
  }, []);
  const load = useCallback(
    () =>
      api<AdminSession[]>('sessions')
        .then(setSessions)
        .catch((e) => setError(e.message)),
    [],
  );
  useEffect(() => {
    let active = true;
    api<AdminSession[]>('sessions')
      .then((v) => {
        if (active) setSessions(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, []);
  async function revoke(e: React.FormEvent) {
    e.preventDefault();
    if (!target) return;
    setBusy(true);
    try {
      await api('sessions/' + target, 'DELETE', { reason });
      close();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Revocation failed');
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <p className="eyebrow">Secure workspace</p>
      <h1>Administrator overview</h1>
      <p className="muted">
        Manage access with least privilege. Marketplace modules await their review checkpoints.
      </p>
      <div className="grid">
        <section className="card">
          <h2>Your access</h2>
          {me.roles.map((r) => (
            <span className="badge" key={r}>
              {r.replaceAll('_', ' ')}
            </span>
          ))}
          <p className="id">{me.user_id}</p>
          <p className="muted">
            Session expires {me.expires_at ? new Date(me.expires_at).toLocaleString() : 'soon'}.
          </p>
        </section>
        <section className="card">
          <h2>Release checkpoint</h2>
          <p>M7.1 · Authentication, RBAC and audit.</p>
          <p className="muted">
            No live marketplace analytics or customer management are enabled here. No outbound
            invitation email is sent.
          </p>
        </section>
      </div>
      <section className="card">
        <h2>Granted permissions</h2>
        <div className="row">
          {me.permissions?.map((p) => (
            <span className="badge" key={p}>
              {p}
            </span>
          ))}
        </div>
      </section>
      <section className="card">
        <h2>Your recent sessions</h2>
        {error && (
          <p role="alert" className="notice error">
            {error}
          </p>
        )}
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Browser</th>
                <th>Created</th>
                <th>Expires</th>
                <th>State</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr key={s.id}>
                  <td>{s.agent_family}</td>
                  <td>{new Date(s.created_at).toLocaleString()}</td>
                  <td>{new Date(s.expires_at).toLocaleString()}</td>
                  <td>{s.revoked_at ? 'Revoked' : s.expired ? 'Expired' : 'Issued'}</td>
                  <td>
                    <button disabled={!!s.revoked_at} onClick={() => setTarget(s.id)}>
                      Revoke
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!sessions.length && <p className="muted">No sessions loaded.</p>}
      </section>
      {target && (
        <ConfirmDialog title="Revoke administrator session" onClose={close}>
          <p>This session will stop authorizing requests immediately after revocation.</p>
          <form onSubmit={revoke}>
            <label htmlFor="revoke-reason">Revocation reason</label>
            <textarea
              id="revoke-reason"
              required
              minLength={3}
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
            <div className="row mt-4">
              <button type="button" onClick={close}>
                Cancel
              </button>
              <button className="primary" disabled={busy}>
                Confirm revocation
              </button>
            </div>
          </form>
        </ConfirmDialog>
      )}
    </>
  );
}
