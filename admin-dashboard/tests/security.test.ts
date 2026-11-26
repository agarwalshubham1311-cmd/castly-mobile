import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  adminCookiePolicy,
  allowedBackend,
  safeOrigin,
  sameOrigin,
  cleanQuery,
  PERMISSION_NAV,
  ROLES,
  browserFamily,
} from '../src/lib/security.ts';
test('proxy exposes only implemented admin contracts', () => {
  for (const path of ['me', 'audit', 'principals', 'sessions'])
    assert.equal(allowedBackend('GET', path), true);
  for (const path of [
    'account',
    '../account',
    'principals/../audit',
    'https://evil.test',
    'audit/secret',
    'sessions//',
  ])
    assert.equal(allowedBackend('GET', path), false);
  assert.equal(allowedBackend('POST', 'invitations/activate'), true);
  assert.equal(allowedBackend('DELETE', 'principals/00000000-0000-0000-0000-000000000001'), false);
  assert.equal(allowedBackend('PATCH', 'principals/00000000-0000-0000-0000-000000000001'), true);
});
test('production origins reject SSRF and insecure transports', () => {
  for (const bad of [
    'http://api.test',
    'https://api.test/path',
    'https://u:p@api.test',
    'https://api.test?token=x',
    'file:///tmp/a',
    'https://api.test#x',
  ])
    assert.throws(() => safeOrigin(bad, true));
  assert.equal(safeOrigin('https://api.test', true), 'https://api.test');
  assert.equal(safeOrigin('http://127.0.0.1:3000', false), 'http://127.0.0.1:3000');
  assert.throws(() => safeOrigin('http://evil.test', false));
});
test('unsafe origins cannot pass CSRF check', () => {
  assert.equal(sameOrigin(null, 'https://admin.test'), false);
  assert.equal(sameOrigin('https://evil.test', 'https://admin.test'), false);
  assert.equal(sameOrigin('https://admin.test', 'https://admin.test'), true);
});
test('query keys and bytes remain bounded', () => {
  assert.equal(
    cleanQuery(new URLSearchParams({ limit: '25', after: '123' })),
    'limit=25&after=123',
  );
  assert.throws(() => cleanQuery(new URLSearchParams({ url: 'http://evil.test' })));
  assert.throws(() => cleanQuery(new URLSearchParams({ after: 'a'.repeat(51) })));
});
test('role controls have no super-admin issuance option', () => {
  assert.equal((ROLES as readonly string[]).includes('SUPER_ADMIN'), false);
  assert.equal(
    PERMISSION_NAV.find((x) => x.href === '/settings/admins')?.permission,
    'admins.manage',
  );
  assert.equal(PERMISSION_NAV.find((x) => x.href === '/audit')?.permission, 'audit.read');
});
test('user agents reduce to bounded coarse families', () => {
  assert.equal(browserFamily('Mozilla Chrome/1'), 'chromium');
  assert.equal(browserFamily('private identifier Firefox/1'), 'firefox');
  assert.equal(browserFamily('random session secret'), 'other');
});

test('production local-test flag cannot downgrade cookies on remote or mixed origins', () => {
  const env = {
    NODE_ENV: 'production',
    CASTLY_ADMIN_LOCAL_TEST: 'true',
    ADMIN_ORIGIN: 'https://admin.fixture.invalid',
    CASTLY_API_URL: 'https://api.fixture.invalid',
    SUPABASE_URL: 'https://auth.fixture.invalid',
  };
  assert.deepEqual(adminCookiePolicy(env), { secure: true, prefix: '__Host-' });
  for (const key of ['ADMIN_ORIGIN', 'CASTLY_API_URL', 'SUPABASE_URL']) {
    const origins = {
      ...env,
      ADMIN_ORIGIN: 'http://localhost:3017',
      CASTLY_API_URL: 'http://127.0.0.1:19180',
      SUPABASE_URL: 'http://localhost:19198',
    };
    for (const invalid of [
      'https://remote.fixture.invalid',
      'http://localhost.evil.test:3017',
      'http://u:p@localhost:3017',
      'http://localhost:3017/path',
      undefined,
    ]) {
      assert.deepEqual(adminCookiePolicy({ ...origins, [key]: invalid }), {
        secure: true,
        prefix: '__Host-',
      });
    }
  }
});
test('cookie exception requires opt-in and three exact loopback fixture origins', () => {
  const env = {
    NODE_ENV: 'production',
    CASTLY_ADMIN_LOCAL_TEST: 'true',
    ADMIN_ORIGIN: 'http://localhost:3017',
    CASTLY_API_URL: 'http://127.0.0.1:19180',
    SUPABASE_URL: 'http://localhost:19198',
  };
  assert.deepEqual(adminCookiePolicy(env), { secure: false, prefix: '' });
  assert.deepEqual(adminCookiePolicy({ ...env, CASTLY_ADMIN_LOCAL_TEST: 'false' }), {
    secure: true,
    prefix: '__Host-',
  });
  assert.deepEqual(adminCookiePolicy({ NODE_ENV: 'production' }), {
    secure: true,
    prefix: '__Host-',
  });
  assert.deepEqual(adminCookiePolicy({ NODE_ENV: 'development' }), { secure: false, prefix: '' });
});

test('user management proxy exposes only minimized fixed contracts', () => {
  const id = '00000000-0000-0000-0000-000000000001';
  for (const p of [
    'users',
    'users/' + id,
    'users/' + id + '/bookings',
    'users/verifications',
    'users/verifications/' + id,
  ])
    assert.equal(allowedBackend('GET', p), true);
  assert.equal(allowedBackend('POST', 'users/verifications/' + id + '/decision'), true);
  for (const p of [
    'users/' + id + '/documents',
    'users/' + id + '/payments',
    'users/../../account',
    'users/verifications/' + id + '/storage',
  ])
    assert.equal(allowedBackend('GET', p), false);
  assert.equal(allowedBackend('DELETE', 'users/' + id), false);
  assert.equal(allowedBackend('PATCH', 'users/' + id), false);
  assert.throws(() => cleanQuery(new URLSearchParams({ q: 'x'.repeat(81) })));
  assert.equal(
    new URLSearchParams(
      cleanQuery(new URLSearchParams({ q: 'a%_ b', role: 'talent', from: '2026-01-01' })),
    ).get('q'),
    'a%_ b',
  );
});

test('M7.2 proxy exposes bounded account contracts without exposing private schema or arbitrary RPC', () => {
  const id = '00000000-0000-0000-0000-000000000080';
  for (const method of ['GET', 'POST'])
    assert.equal(allowedBackend(method, 'users/' + id + '/access'), true);
  for (const queue of ['appeals', 'approvals', 'overdue'])
    assert.equal(allowedBackend('GET', 'account-queues/' + queue), true);
  for (const path of [
    'account-queues/anything',
    'enforcement_private/commands',
    'rpc/process_command',
    'users/' + id + '/access/../audit',
  ])
    assert.equal(allowedBackend('POST', path), false);
  assert.equal(allowedBackend('POST', 'account-queues/appeals'), false);
  assert.equal(
    cleanQuery(new URLSearchParams({ state: 'TEMPORARILY_SUSPENDED', limit: '25' })),
    'state=TEMPORARILY_SUSPENDED&limit=25',
  );
});
