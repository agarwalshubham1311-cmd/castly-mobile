'use client';
import { useState, useEffect, useCallback } from 'react';
import { api, type Principal } from '@/lib/client';
import { useAdmin } from '@/components/AdminShell';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { ROLES } from '@/lib/security';
export default function Admins() {
  const me = useAdmin(),
    [rows, setRows] = useState<Principal[]>([]),
    [cursor, setCursor] = useState(''),
    [history, setHistory] = useState<string[]>([]),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [target, setTarget] = useState<Principal | null>(null),
    [roles, setRoles] = useState<string[]>([]),
    [state, setState] = useState('active'),
    [reason, setReason] = useState(''),
    [inviteUser, setInviteUser] = useState(''),
    [inviteRole, setInviteRole] = useState('ANALYST'),
    [issued, setIssued] = useState<{ token: string; expires_at: string } | null>(null);
  const canManage = me.permissions?.includes('admins.manage');
  const close = useCallback(() => {
    setTarget(null);
    setReason('');
  }, []);
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(
        await api<Principal[]>(
          'principals?limit=25' + (cursor ? '&after=' + encodeURIComponent(cursor) : ''),
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Directory unavailable');
    } finally {
      setLoading(false);
    }
  }, [cursor]);
  useEffect(() => {
    if (!canManage) return;
    let active = true;
    api<Principal[]>('principals?limit=25' + (cursor ? '&after=' + encodeURIComponent(cursor) : ''))
      .then((v) => {
        if (active) setRows(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [cursor, canManage]);
  function edit(row: Principal) {
    setTarget(row);
    setRoles(row.roles);
    setState(row.state);
    setReason('');
    setError('');
  }
  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!target) return;
    setBusy(true);
    setError('');
    try {
      await api('principals/' + target.user_id, 'PATCH', {
        roles,
        state,
        expected_version: target.version,
        reason,
      });
      close();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Access change failed');
    } finally {
      setBusy(false);
    }
  }
  async function invite(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setIssued(null);
    setError('');
    try {
      setIssued(
        await api('invitations', 'POST', { user_id: inviteUser, roles: [inviteRole], reason }),
      );
      setInviteUser('');
      setReason('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Invitation failed');
    } finally {
      setBusy(false);
    }
  }
  if (!canManage)
    return (
      <section className="card">
        <h1>Access restricted</h1>
        <p>Administrator management permission is required.</p>
      </section>
    );
  return (
    <>
      <p className="eyebrow">Access control</p>
      <h1>Administrators</h1>
      <p className="muted">
        Invite existing accounts, assign least privilege and revoke access. Changes expire previous
        sessions.
      </p>
      {error && !target && (
        <p role="alert" className="notice error">
          {error}
        </p>
      )}
      <section className="card">
        <div className="row">
          <h2 className="grow">Administrator directory</h2>
          <button onClick={load} disabled={loading}>
            Refresh
          </button>
        </div>
        {loading ? (
          <p role="status">Loading administrators…</p>
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Account reference</th>
                    <th>Roles</th>
                    <th>Access</th>
                    <th>Version</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.user_id}>
                      <td className="id">
                        {r.user_id}
                        {r.user_id === me.user_id ? ' (you)' : ''}
                      </td>
                      <td>
                        {r.roles.map((x) => (
                          <span key={x} className="badge">
                            {x.replaceAll('_', ' ')}
                          </span>
                        ))}
                      </td>
                      <td>{r.state}</td>
                      <td>{r.version}</td>
                      <td>
                        <button
                          disabled={r.user_id === me.user_id || r.roles.includes('SUPER_ADMIN')}
                          onClick={() => edit(r)}
                        >
                          Manage access
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {rows.length === 0 && <p>No administrators on this page.</p>}
            <div className="row mt-4">
              <button
                disabled={!history.length}
                onClick={() => {
                  setLoading(true);
                  setCursor(history.at(-1) ?? '');
                  setHistory((h) => h.slice(0, -1));
                }}
              >
                Previous
              </button>
              <button
                disabled={rows.length < 25}
                onClick={() => {
                  setLoading(true);
                  setHistory((h) => [...h, cursor]);
                  setCursor(rows.at(-1)!.user_id);
                }}
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
      <section className="card">
        <h2>Invite an existing account</h2>
        <p className="muted">
          No email is sent. Share the one-time secret only through an approved secure channel.
          Super-admin provisioning is unavailable here.
        </p>
        <form onSubmit={invite} className="max-w-xl">
          <label htmlFor="invite-user">Approved account UUID</label>
          <input
            id="invite-user"
            required
            pattern="[0-9a-fA-F-]{36}"
            maxLength={36}
            value={inviteUser}
            onChange={(e) => setInviteUser(e.target.value)}
          />
          <label htmlFor="invite-role">Initial role</label>
          <select
            id="invite-role"
            value={inviteRole}
            onChange={(e) => setInviteRole(e.target.value)}
          >
            {ROLES.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
          <label htmlFor="invite-reason">Approval reason</label>
          <textarea
            id="invite-reason"
            required
            minLength={3}
            maxLength={500}
            value={target ? '' : reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <button className="primary mt-4" disabled={busy || !!target}>
            {busy ? 'Submitting…' : 'Create invitation'}
          </button>
        </form>
        {issued && (
          <div className="notice mt-4">
            <strong>One-time invitation secret</strong>
            <p>
              Expires {new Date(issued.expires_at).toLocaleString()}. This secret is not shown
              again.
            </p>
            <pre data-testid="invitation-secret">{issued.token}</pre>
            <button onClick={() => setIssued(null)}>Clear secret</button>
          </div>
        )}
      </section>
      {target && (
        <ConfirmDialog title="Change administrator access" onClose={close}>
          <p className="id">{target.user_id}</p>
          <p>
            All existing sessions for this account will expire. Reauthentication within five minutes
            is required.
          </p>
          {error && (
            <p role="alert" className="notice error">
              {error}
            </p>
          )}
          <form onSubmit={save}>
            <div className="checklist">
              {ROLES.map((r) => (
                <label className="checkbox" key={r}>
                  <input
                    type="checkbox"
                    checked={roles.includes(r)}
                    onChange={(e) =>
                      setRoles((current) =>
                        e.target.checked ? [...current, r] : current.filter((x) => x !== r),
                      )
                    }
                  />
                  {r.replaceAll('_', ' ')}
                </label>
              ))}
            </div>
            <label htmlFor="access-state">Access state</label>
            <select id="access-state" value={state} onChange={(e) => setState(e.target.value)}>
              <option value="active">Active</option>
              <option value="revoked">Revoked</option>
            </select>
            <label htmlFor="change-reason">Reason for change</label>
            <textarea
              id="change-reason"
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
              <button className="primary" disabled={busy || !roles.length}>
                {busy ? 'Saving…' : 'Confirm access change'}
              </button>
            </div>
          </form>
        </ConfirmDialog>
      )}
    </>
  );
}
