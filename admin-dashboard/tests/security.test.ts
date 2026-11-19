import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
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
