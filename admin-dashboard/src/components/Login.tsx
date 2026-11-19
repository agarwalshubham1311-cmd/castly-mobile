'use client';
import { useRouter } from 'next/navigation';
import { useState, useEffect } from 'react';
import Link from 'next/link';
import { auth } from '@/lib/client';
export function Login({
  mfa = false,
  revocationUnconfirmed = false,
}: {
  mfa?: boolean;
  revocationUnconfirmed?: boolean;
}) {
  const router = useRouter();
  const [email, setEmail] = useState(''),
    [password, setPassword] = useState(''),
    [code, setCode] = useState(''),
    [factor, setFactor] = useState(''),
    [challenge, setChallenge] = useState(''),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [verified, setVerified] = useState(false);
  useEffect(() => {
    if (!mfa) return;
    let active = true;
    auth<{ factors: { id: string }[] }>('factors')
      .then((r) => {
        if (active) {
          if (!r.factors.length)
            setError('No verified TOTP factor. Contact operations for approved MFA enrollment.');
          else setFactor(r.factors[0].id);
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [mfa]);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (!mfa) {
        await auth('login', { email, password });
        setPassword('');
        router.push('/login/mfa');
        router.refresh();
      } else {
        let id = challenge;
        if (!id) {
          const c = await auth<{ challenge_id: string }>('challenge', { factor_id: factor });
          id = c.challenge_id;
          setChallenge(id);
        }
        await auth('verify', { factor_id: factor, challenge_id: id, code });
        setCode('');
        setChallenge('');
        setVerified(true);
        try {
          await auth('session');
          router.push('/dashboard');
          router.refresh();
        } catch {
          setError(
            'MFA verified. If invited, activate your invitation below; otherwise contact operations.',
          );
        }
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Sign-in failed');
      setChallenge('');
    } finally {
      setBusy(false);
    }
  }
  return (
    <main id="main" className="auth">
      <div className="brand">castly✦</div>
      <p className="eyebrow">Operations studio</p>
      <section className="card">
        {revocationUnconfirmed && (
          <p className="notice error" role="alert">
            Local cookies were cleared, but server revocation could not be confirmed. Contact
            operations if this was a security sign-out.
          </p>
        )}
        <h1>{mfa ? 'Verify your access' : 'Welcome back'}</h1>
        <p className="muted">
          {mfa
            ? 'Enter the six-digit code from your approved authenticator.'
            : 'Sign in with your Castly administrator account. TOTP MFA is required.'}
        </p>
        {error && (
          <p className="notice error" role="alert">
            {error}
          </p>
        )}
        <form onSubmit={submit}>
          {mfa ? (
            <>
              <label htmlFor="code">Authenticator code</label>
              <input
                id="code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                required
                maxLength={6}
              />
            </>
          ) : (
            <>
              <label htmlFor="email">Email address</label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                maxLength={254}
              />
              <label htmlFor="password">Password</label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                maxLength={256}
              />
            </>
          )}
          <button className="primary w-full mt-5" disabled={busy || (mfa && !factor)}>
            {busy ? 'Verifying…' : mfa ? 'Verify MFA' : 'Continue securely'}
          </button>
        </form>
        {mfa && (
          <p className="mt-4">
            <Link href="/activate">
              Activate an approved invitation{verified ? ' · MFA verified' : ''}
            </Link>
          </p>
        )}
        <p className="muted mt-4">
          No administrator? Access is by separately approved invitation only.
        </p>
      </section>
    </main>
  );
}
