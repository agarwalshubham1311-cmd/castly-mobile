'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import Link from 'next/link';
import { api, auth } from '@/lib/client';
export default function Page() {
  const router = useRouter();
  const [token, setToken] = useState(''),
    [reason, setReason] = useState(''),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  async function activate(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api('invitations/activate', 'POST', { token, reason });
      setToken('');
      await auth('session');
      router.push('/dashboard');
      router.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Activation failed');
    } finally {
      setBusy(false);
    }
  }
  return (
    <main id="main" className="auth">
      <div className="card">
        <h1>Activate invitation</h1>
        <p>
          Sign in and verify MFA first. Invitations are single-use and bound to the approved
          account.
        </p>
        <Link href="/login/mfa">Verify MFA</Link>
        {error && (
          <p role="alert" className="notice error">
            {error}
          </p>
        )}
        <form onSubmit={activate}>
          <label htmlFor="token">Invitation secret</label>
          <input
            id="token"
            type="password"
            autoComplete="off"
            required
            minLength={43}
            maxLength={43}
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
          <label htmlFor="reason">Acceptance reason</label>
          <input
            id="reason"
            required
            minLength={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <button className="primary mt-5" disabled={busy}>
            {busy ? 'Activating…' : 'Activate access'}
          </button>
        </form>
      </div>
    </main>
  );
}
