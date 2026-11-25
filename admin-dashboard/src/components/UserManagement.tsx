'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/lib/client';
import { useAdmin } from './AdminShell';
import type { Page, UserSummary, UserDetail, BookingSummary } from '@/lib/users';

export function useResource<T>(path: string, allowed: boolean) {
  const user = useAdmin();
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<{ key: string; data: T | null; error: string } | null>(null);
  const key = `${user.user_id}/${user.version}/${path}/${version}`;
  useEffect(() => {
    let active = true;
    if (!allowed) return;
    api<T>(path)
      .then((data) => {
        if (active) setResult({ key, data, error: '' });
      })
      .catch((error) => {
        if (active)
          setResult({
            key,
            data: null,
            error: error instanceof Error ? error.message : 'Request failed',
          });
      });
    return () => {
      active = false;
    };
  }, [path, allowed, key]);
  const current = allowed && result?.key === key ? result : null;
  return {
    data: current?.data ?? null,
    error: current?.error ?? '',
    loading: allowed && current === null,
    retry: () => setVersion((x) => x + 1),
  };
}
export function UserDirectory() {
  const me = useAdmin(),
    allowed = !!me.modules?.user_management && !!me.permissions?.includes('users.read');
  const [filters, setFilters] = useState({
      q: '',
      role: '',
      verification: '',
      from: '',
      until: '',
    }),
    [query, setQuery] = useState(''),
    [after, setAfter] = useState(''),
    [back, setBack] = useState<string[]>([]);
  const { data, error, loading, retry } = useResource<Page<UserSummary>>(
    'users?limit=25' + (query ? '&' + query : '') + (after ? '&after=' + after : ''),
    allowed,
  );
  if (!allowed)
    return (
      <section className="card">
        <h1>Access restricted</h1>
        <p>User management is unavailable for this session.</p>
      </section>
    );
  return (
    <>
      <p className="eyebrow">People and profiles</p>
      <h1>User directory</h1>
      <p className="muted">
        Search by name prefix. Summaries exclude contact details, identity documents and payment
        identifiers.
      </p>
      <section className="card">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setAfter('');
            setBack([]);
            setQuery(new URLSearchParams(Object.entries(filters).filter(([, v]) => v)).toString());
          }}
        >
          <div className="grid">
            {(['q', 'role', 'verification', 'from', 'until'] as const).map((key) => (
              <div key={key}>
                <label htmlFor={'filter-' + key}>
                  {
                    {
                      q: 'Name prefix',
                      role: 'Account type',
                      verification: 'Verification state',
                      from: 'Created from',
                      until: 'Created through',
                    }[key]
                  }
                </label>
                {key === 'role' ? (
                  <select
                    id={'filter-' + key}
                    value={filters[key]}
                    onChange={(e) => setFilters({ ...filters, [key]: e.target.value })}
                  >
                    <option value="">All account types</option>
                    <option value="client">Client</option>
                    <option value="talent">Talent</option>
                  </select>
                ) : key === 'verification' ? (
                  <select
                    id={'filter-' + key}
                    value={filters[key]}
                    onChange={(e) => setFilters({ ...filters, [key]: e.target.value })}
                  >
                    <option value="">All verification states</option>
                    {[
                      'NOT_SUBMITTED',
                      'PENDING',
                      'UNDER_REVIEW',
                      'VERIFIED',
                      'REJECTED',
                      'EXPIRED',
                      'REVOKED',
                    ].map((x) => (
                      <option key={x}>{x}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    id={'filter-' + key}
                    type={key === 'q' ? 'search' : 'date'}
                    minLength={key === 'q' ? 2 : undefined}
                    maxLength={key === 'q' ? 80 : undefined}
                    value={filters[key]}
                    onChange={(e) => setFilters({ ...filters, [key]: e.target.value })}
                  />
                )}
              </div>
            ))}
          </div>
          <button className="primary mt-4">Apply filters</button>
        </form>
      </section>
      <section className="card">
        <div className="row">
          <h2 className="grow">Accounts</h2>
          <button onClick={retry}>Refresh</button>
        </div>
        {error ? (
          <p role="alert">
            {error} <button onClick={retry}>Retry</button>
          </p>
        ) : loading ? (
          <p role="status">Loading accounts…</p>
        ) : (
          <>
            <div className="table-scroll">
              <table className="min-w-[720px]">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Account type</th>
                    <th>Verification</th>
                    <th>Created</th>
                    <th>Talent profile</th>
                  </tr>
                </thead>
                <tbody>
                  {data?.items.map((u) => (
                    <tr key={u.user_id}>
                      <td>
                        <Link className="whitespace-nowrap" href={'/users/' + u.user_id}>
                          {u.display_name || 'Unnamed account'}
                        </Link>
                      </td>
                      <td>{u.primary_role ?? 'Not onboarded'}</td>
                      <td>{u.verification_status}</td>
                      <td>{new Date(u.created_at).toLocaleDateString()}</td>
                      <td>
                        {u.category_id
                          ? `${u.category_id} · ${u.city} · ${u.published ? 'Published' : 'Unpublished'}`
                          : 'No talent profile'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!data?.items.length && <p>No matching accounts.</p>}
            <div className="row mt-4">
              <button
                disabled={!back.length}
                onClick={() => {
                  setAfter(back[back.length - 1]);
                  setBack(back.slice(0, -1));
                }}
              >
                Previous
              </button>
              <button
                disabled={!data?.next_after}
                onClick={() => {
                  setBack([...back, after]);
                  setAfter(data!.next_after!);
                }}
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </>
  );
}
export function UserDetails({ id }: { id: string }) {
  const me = useAdmin(),
    allowed = !!me.modules?.user_management && !!me.permissions?.includes('users.read'),
    canBookings = allowed && !!me.permissions?.includes('bookings.read');
  const detail = useResource<UserDetail>('users/' + id, allowed),
    [after, setAfter] = useState(''),
    bookings = useResource<Page<BookingSummary>>(
      'users/' + id + '/bookings?limit=25' + (after ? '&after=' + after : ''),
      canBookings,
    );
  if (!allowed)
    return (
      <section className="card">
        <h1>Access restricted</h1>
      </section>
    );
  if (detail.error)
    return (
      <section className="card">
        <h1>Account unavailable</h1>
        <p role="alert">{detail.error}</p>
        <button onClick={detail.retry}>Retry</button>
      </section>
    );
  if (detail.loading || !detail.data) return <p role="status">Loading account…</p>;
  const d = detail.data;
  return (
    <>
      <Link href="/users">Back to users</Link>
      <h1 className="mt-4">{d.account.display_name || 'Unnamed account'}</h1>
      <section className="card">
        <h2>Account summary</h2>
        <p className="break-all">Reference: {id}</p>
        <p>
          Account type: {d.account.primary_role ?? 'Not onboarded'} · Verification:{' '}
          {d.account.verification_status}
        </p>
        <p>
          Created: {new Date(d.account.created_at).toLocaleString()} · Last account update:{' '}
          {new Date(d.account.updated_at).toLocaleString()}
        </p>
      </section>
      <section className="card">
        <h2>Talent profile</h2>
        {d.talent.length ? (
          d.talent.map((t) => (
            <div key={t.user_id}>
              <h3>{t.display_name}</h3>
              <p>
                {t.category_id} · {t.city}
              </p>
              <p>
                Indicative day rate: ₹{t.day_rate.toLocaleString()} ·{' '}
                {t.published ? 'Published' : 'Unpublished'} ·{' '}
                {t.available ? 'Available' : 'Unavailable'}
              </p>
            </div>
          ))
        ) : (
          <p>No associated talent profile.</p>
        )}
      </section>
      <section className="card">
        <h2>Subscription status</h2>
        <p className="muted">
          Up to 50 entitlement records, including legacy and promotional access. Active access does
          not establish a paid subscription or settled revenue.
        </p>
        {d.subscriptions.length ? (
          d.subscriptions.map((g, i) => (
            <p key={i}>
              {g.entitlement} · {g.active ? 'Active' : 'Inactive'} · {g.source} ·{' '}
              {g.expires_at ? new Date(g.expires_at).toLocaleString() : 'No expiry recorded'}
            </p>
          ))
        ) : (
          <p>No entitlement records.</p>
        )}
      </section>
      {canBookings && (
        <section className="card">
          <h2>Relevant bookings</h2>
          {bookings.error ? (
            <p role="alert">
              {bookings.error} <button onClick={bookings.retry}>Retry bookings</button>
            </p>
          ) : bookings.loading ? (
            <p role="status">Loading bookings…</p>
          ) : (
            <>
              <div className="table-scroll">
                <table className="min-w-[720px]">
                  <thead>
                    <tr>
                      <th>Project</th>
                      <th>Date</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {bookings.data?.items.map((b) => (
                      <tr key={b.id}>
                        <td>{b.project_type}</td>
                        <td>{b.proposed_date}</td>
                        <td>{b.status}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!bookings.data?.items.length && <p>No relevant bookings.</p>}
              <div className="row mt-4">
                <button disabled={!after} onClick={() => setAfter('')}>
                  First page
                </button>
                <button
                  disabled={!bookings.data?.next_after}
                  onClick={() => setAfter(bookings.data!.next_after!)}
                >
                  More bookings
                </button>
              </div>
            </>
          )}
        </section>
      )}
      <section className="card">
        <h2>Account history</h2>
        <p className="muted">{d.history_note} Most recent 50 events.</p>
        {d.history.map((h) => (
          <p key={h.id}>
            {h.action} · {new Date(h.occurred_at).toLocaleString()}
          </p>
        ))}
        {!d.history.length && <p>No recorded events.</p>}
      </section>
      <section className="card">
        <h2>Security activity</h2>
        {d.security_activity?.events.map((e, i) => (
          <p key={i}>
            {e.action} · {e.outcome} · {new Date(e.occurred_at).toLocaleString()}
          </p>
        ))}
        {d.security_activity && !d.security_activity.events.length && (
          <p>No recorded privileged-access denials.</p>
        )}
        <p>
          {d.security_activity
            ? d.security_activity.note
            : 'Audit permission is required to inspect privileged-access denials. No fraud assessment or complete identity-provider history is inferred.'}
        </p>
      </section>
    </>
  );
}
