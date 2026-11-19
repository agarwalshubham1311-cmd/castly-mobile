'use client';
import { useState, useEffect, useCallback } from 'react';
import { api } from '@/lib/client';
import { useAdmin } from '@/components/AdminShell';
interface Audit {
  id: number;
  actor_id: string | null;
  action: string;
  target_id: string | null;
  occurred_at: string;
  request_id: string;
  reason: string;
  outcome: string;
  before_state: object | string;
  after_state: object | string;
}
export default function AuditPage() {
  const user = useAdmin(),
    [rows, setRows] = useState<Audit[]>([]),
    [cursor, setCursor] = useState(''),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(true);
  const permitted = user.permissions?.includes('audit.read');
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setRows(await api<Audit[]>('audit?limit=25' + (cursor ? '&before=' + cursor : '')));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Audit unavailable');
    } finally {
      setLoading(false);
    }
  }, [cursor]);
  useEffect(() => {
    if (!permitted) return;
    let active = true;
    api<Audit[]>('audit?limit=25' + (cursor ? '&before=' + cursor : ''))
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
  }, [cursor, permitted]);
  if (!permitted)
    return (
      <section className="card">
        <h1>Access restricted</h1>
        <p>Audit permission is required.</p>
      </section>
    );
  return (
    <>
      <p className="eyebrow">Accountability</p>
      <h1>Audit history</h1>
      <p className="muted">
        Append-only application records. Secrets and identity documents are excluded.
      </p>
      <section className="card">
        {error && (
          <p role="alert" className="notice error">
            {error}
          </p>
        )}
        {loading ? (
          <p role="status">Loading audit history…</p>
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>When / outcome</th>
                    <th>Action</th>
                    <th>Actor / target</th>
                    <th>Reason / changes</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        {new Date(r.occurred_at).toLocaleString()}
                        <br />
                        <span className="badge">{r.outcome}</span>
                      </td>
                      <td>
                        <strong>{r.action}</strong>
                        <div className="id">Request {r.request_id}</div>
                      </td>
                      <td className="id">
                        {r.actor_id ?? 'Reviewed bootstrap'}
                        <br />
                        {r.target_id ?? '—'}
                      </td>
                      <td>
                        {r.reason}
                        <details>
                          <summary>Safe change details</summary>
                          <pre>
                            {typeof r.before_state === 'string'
                              ? r.before_state
                              : JSON.stringify(r.before_state)}{' '}
                            →{' '}
                            {typeof r.after_state === 'string'
                              ? r.after_state
                              : JSON.stringify(r.after_state)}
                          </pre>
                        </details>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!rows.length && <p>No audit records on this page.</p>}
            <div className="row mt-4">
              <button
                onClick={() => {
                  setCursor('');
                  if (!cursor) load();
                }}
              >
                Latest
              </button>
              <button
                disabled={rows.length < 25}
                onClick={() => {
                  setLoading(true);
                  setCursor(String(rows.at(-1)!.id));
                }}
              >
                Older records
              </button>
            </div>
          </>
        )}
      </section>
    </>
  );
}
