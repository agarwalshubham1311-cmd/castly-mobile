import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import {
  ACCESS,
  REFRESH,
  SESSION,
  apiRequest,
  authRequest,
  boundedResponse,
  boundedInput,
  clearTokens,
  configuration,
  loginLimit,
  writeTokens,
} from '@/lib/server';
import { sameOrigin } from '@/lib/security';
export async function POST(request: Request, context: { params: Promise<{ action: string }> }) {
  try {
    const c = configuration();
    if (!sameOrigin(request.headers.get('origin'), c.origin))
      return NextResponse.json({ detail: 'Origin rejected' }, { status: 403 });
    if (!request.headers.get('content-type')?.startsWith('application/json'))
      return NextResponse.json({ detail: 'JSON required' }, { status: 415 });
    const text = await boundedInput(request, 8192);
    if (text.length > 8192)
      return NextResponse.json({ detail: 'Request too large' }, { status: 413 });
    const input = JSON.parse(text),
      { action } = await context.params;
    const jar = await cookies(),
      access = jar.get(ACCESS)?.value;
    if (action === 'login') {
      loginLimit();
      if (
        typeof input.email !== 'string' ||
        input.email.length > 254 ||
        typeof input.password !== 'string' ||
        input.password.length > 256
      )
        return NextResponse.json({ detail: 'Invalid credentials' }, { status: 400 });
      await clearTokens();
      const tokens = await authRequest('token?grant_type=password', 'POST', {
        email: input.email,
        password: input.password,
      });
      await writeTokens(tokens);
      return NextResponse.json({ next: '/login/mfa' });
    }
    if (action === 'logout') {
      let revoked = true;
      try {
        const session = jar.get(SESSION)?.value;
        if (session) {
          const r = await apiRequest('sessions/' + session, 'DELETE', {
            reason: 'Administrator logout',
          });
          revoked = r.ok;
        }
        if (access) await authRequest('logout?scope=local', 'POST', {}, access);
      } catch {
        revoked = false;
      } finally {
        await clearTokens();
      }
      return NextResponse.json({
        revoked,
        detail: revoked
          ? 'Signed out'
          : 'Local session cleared. Server revocation could not be confirmed; contact operations.',
      });
    }
    if (!access) return NextResponse.json({ detail: 'Sign in required' }, { status: 401 });
    if (action === 'factors') {
      const user = await authRequest('user', 'GET', undefined, access);
      const factors = (user.factors ?? [])
        .filter(
          (f: { factor_type: string; status: string }) =>
            f.factor_type === 'totp' && f.status === 'verified',
        )
        .map((f: { id: string }) => ({ id: f.id }));
      return NextResponse.json({ factors });
    }
    if (action === 'challenge' || action === 'verify') {
      loginLimit();
      if (typeof input.factor_id !== 'string' || !/^[0-9a-f-]{36}$/.test(input.factor_id))
        return NextResponse.json({ detail: 'Invalid factor' }, { status: 400 });
      if (action === 'challenge') {
        const result = await authRequest(
          'factors/' + input.factor_id + '/challenge',
          'POST',
          {},
          access,
        );
        return NextResponse.json({ challenge_id: result.id });
      }
      if (
        typeof input.challenge_id !== 'string' ||
        !/^[0-9a-f-]{36}$/.test(input.challenge_id) ||
        !/^\d{6}$/.test(input.code ?? '')
      )
        return NextResponse.json({ detail: 'Invalid code' }, { status: 400 });
      const tokens = await authRequest(
        'factors/' + input.factor_id + '/verify',
        'POST',
        { challenge_id: input.challenge_id, code: input.code },
        access,
      );
      await writeTokens(tokens);
      // The backend creates an admin session separately; activation is available to invited non-admin accounts.
      return NextResponse.json({ verified: true });
    }
    if (action === 'session') {
      const response = await apiRequest('sessions', 'POST');
      const body = await boundedResponse(response);
      if (!response.ok)
        return NextResponse.json(
          {
            detail:
              response.status === 403
                ? 'Administrator access or fresh MFA required'
                : 'Administrator session unavailable',
          },
          { status: response.status },
        );
      const result = JSON.parse(body);
      const previous = jar.get(SESSION)?.value;
      jar.set(SESSION, result.session.id, {
        httpOnly: true,
        secure: c.secure,
        sameSite: 'strict',
        path: '/',
        maxAge: 3600,
      });
      // Role/session verification remains server-side; do not send bearer credentials to browser.
      if (previous) {
        try {
          await apiRequest('sessions/' + previous, 'DELETE', {
            reason: 'Reauthenticated administrator session',
          });
        } catch {
          /* expiry/version checks still bound old session */
        }
      }
      return NextResponse.json({ next: '/dashboard' });
    }
    if (action === 'refresh') {
      const refresh = jar.get(REFRESH)?.value;
      if (!refresh) return NextResponse.json({ detail: 'Sign in required' }, { status: 401 });
      await writeTokens(
        await authRequest('token?grant_type=refresh_token', 'POST', { refresh_token: refresh }),
      );
      return NextResponse.json({ refreshed: true });
    }
    return NextResponse.json({ detail: 'Unavailable' }, { status: 404 });
  } catch {
    return NextResponse.json(
      { detail: 'Identity verification unavailable or rejected. Retry or contact operations.' },
      { status: 403 },
    );
  }
}
