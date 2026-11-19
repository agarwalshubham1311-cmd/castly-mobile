'use client';
import { createContext, useContext, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { api, auth, type Principal } from '@/lib/client';
import { PERMISSION_NAV } from '@/lib/security';
const Context = createContext<Principal | null>(null);
export function useAdmin() {
  const user = useContext(Context);
  if (!user) throw new Error('Admin context required');
  return user;
}
export function AdminShell({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<Principal | null>(null),
    [error, setError] = useState(''),
    [open, setOpen] = useState(false);
  const path = usePathname();
  async function load() {
    setError('');
    try {
      setUser(await api<Principal>('me'));
    } catch (e) {
      setUser(null);
      setError(e instanceof Error ? e.message : 'Session unavailable');
    }
  }
  useEffect(() => {
    let active = true;
    api<Principal>('me')
      .then((v) => {
        if (active) setUser(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    const timer = setInterval(() => {
      api<Principal>('me')
        .then((v) => {
          if (active) setUser(v);
        })
        .catch((e) => {
          if (active) {
            setUser(null);
            setError(e.message);
          }
        });
    }, 60000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, []);
  async function logout() {
    try {
      const result = await auth<{ revoked: boolean }>('logout');
      window.location.assign(result.revoked ? '/login' : '/login?revocation=unconfirmed');
    } catch {
      setError('Sign-out could not be confirmed. Retry or contact operations.');
    }
  }
  if (!user)
    return (
      <main id="main" className="auth">
        <div className="card">
          <div className="brand">castly✦</div>
          <h1>Restricted operations</h1>
          {error ? (
            <>
              <p role="alert">{error}</p>
              <button onClick={load}>Retry session</button> <Link href="/login">Sign in again</Link>
            </>
          ) : (
            <p role="status">Verifying administrator session…</p>
          )}
        </div>
      </main>
    );
  return (
    <Context value={user}>
      <div className="shell">
        <aside aria-label="Primary navigation" className={'sidebar' + (open ? ' open' : '')}>
          <div className="brand">castly✦</div>
          <p className="eyebrow">Operations studio</p>
          <nav>
            {PERMISSION_NAV.filter(
              (x) => !x.permission || user.permissions?.includes(x.permission),
            ).map((x) => (
              <Link
                key={x.href}
                href={x.href}
                aria-current={path === x.href ? 'page' : undefined}
                onClick={() => setOpen(false)}
              >
                {x.label}
              </Link>
            ))}
          </nav>
          <hr />
          <p className="eyebrow">Pending milestones</p>
          {[
            'Users',
            'Verifications',
            'Bookings',
            'Reports',
            'Moderation',
            'Subscriptions',
            'Analytics',
          ].map((x) => (
            <div key={x} className="planned">
              {x} · not enabled
            </div>
          ))}
        </aside>
        <div className="workspace">
          <header className="topbar">
            <button className="menu-button" aria-expanded={open} onClick={() => setOpen(!open)}>
              Menu
            </button>
            <div>
              <strong>
                Operations /{' '}
                {path.includes('admins')
                  ? 'Administrators'
                  : path.includes('audit')
                    ? 'Audit logs'
                    : 'Overview'}
              </strong>
              <div className="muted">Privileged access · MFA verified</div>
            </div>
            <div className="row">
              <Link href="/login/mfa">Reauthenticate</Link>
              <button onClick={logout}>Sign out</button>
            </div>
          </header>
          <main id="main" className="content">
            {children}
          </main>
          <footer>
            Castly administration · privileged actions require a reason and are audited.
          </footer>
        </div>
      </div>
    </Context>
  );
}
