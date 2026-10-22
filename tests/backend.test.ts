import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { backendConfig } from '../src/backend/config.ts';
import { talentPayload, validateTalent } from '../src/backend/validation.ts';
import { portfolioImageType } from '../src/backend/media.ts';
import { readableError, requireData } from '../src/backend/errors.ts';
const require = createRequire(import.meta.url);
const { checkClientEnvironment } = require('../scripts/check-client-env.cjs');
const anon = `e30.${Buffer.from(JSON.stringify({ role: 'anon' })).toString('base64url')}.test`;
const service = `e30.${Buffer.from(JSON.stringify({ role: 'service_role' })).toString('base64url')}.test`;
test('backend mode preserves mock without credentials and fails closed for partial or privileged configuration', () => {
  assert.deepEqual(backendConfig({}), { mode: 'mock' });
  assert.equal(backendConfig({ url: 'https://example.supabase.co' }).mode, 'invalid');
  assert.equal(backendConfig({ mode: 'supabase' }).mode, 'invalid');
  for (const key of [service, 'sb_secret_nope', 'invalid'])
    assert.equal(backendConfig({ url: 'https://example.supabase.co', key }).mode, 'invalid');
  assert.equal(backendConfig({ url: 'http://example.com', key: anon }).mode, 'invalid');
  assert.equal(backendConfig({ url: 'https://example.supabase.co', key: anon }).mode, 'supabase');
  assert.equal(
    backendConfig({ url: 'http://127.0.0.1:54321', key: 'sb_publishable_test' }).mode,
    'supabase',
  );
  assert.equal(
    backendConfig({ url: 'https://example.supabase.co', key: service, mode: 'mock' }).mode,
    'mock',
  );
});
test('Expo build guard rejects service-role/secret credentials in public variables and dotenv files', () => {
  const folder = mkdtempSync(join(tmpdir(), 'castly-env-test-'));
  try {
    assert.doesNotThrow(() =>
      checkClientEnvironment({ EXPO_PUBLIC_SUPABASE_ANON_KEY: anon }, folder),
    );
    for (const key of [service, 'sb_secret_nope'])
      assert.throws(
        () => checkClientEnvironment({ EXPO_PUBLIC_SUPABASE_ANON_KEY: key }, folder),
        /Refusing to bundle/,
      );
    writeFileSync(join(folder, '.env.local'), `EXPO_PUBLIC_SUPABASE_ANON_KEY=${service}\n`);
    assert.throws(() => checkClientEnvironment({}, folder), /Refusing to bundle/);
  } finally {
    rmSync(folder, { recursive: true });
  }
});
test('talent validation rejects invalid rates/names/skills and strips server-managed columns from writes', () => {
  const draft = {
    display_name: 'Actor name',
    city: 'Mumbai',
    category_id: 'actors',
    day_rate: 18000,
    bio: 'Creative portfolio',
    skills: ['Acting'],
    available: true,
    published: false,
    portrait_path: null,
  };
  assert.equal(validateTalent(draft), '');
  for (const value of [-1, NaN, Infinity, 1.5, 10000001])
    assert.match(validateTalent({ ...draft, day_rate: value }), /whole daily rate/);
  assert.match(validateTalent({ ...draft, city: '' }), /city/);
  assert.match(validateTalent({ ...draft, skills: Array(21).fill('skill') }), /20 skills/);
  const payload = talentPayload({
    ...draft,
    id: 'another-owner',
    created_at: 'forged',
    primary_role: 'admin',
  } as typeof draft);
  assert.equal('id' in payload, false);
  assert.equal('primary_role' in payload, false);
  assert.equal('created_at' in payload, false);
});
test('portfolio upload detects supported image content and rejects oversized or unsupported files', () => {
  assert.equal(portfolioImageType(Uint8Array.from([255, 216, 255])).contentType, 'image/jpeg');
  assert.equal(
    portfolioImageType(Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10])).extension,
    'png',
  );
  assert.equal(
    portfolioImageType(Uint8Array.from(Buffer.from('RIFF0000WEBP'))).contentType,
    'image/webp',
  );
  assert.throws(() => portfolioImageType(new Uint8Array(5242881)), /smaller than 5MB/);
  assert.throws(
    () => portfolioImageType(Uint8Array.from(Buffer.from('not an image'))),
    /not supported/,
  );
});
test('network failures remain explicit errors and never resolve as successful writes', () => {
  assert.match(readableError(new TypeError('Failed to fetch')), /Check your connection/);
  assert.throws(
    () => requireData({ data: null, error: new Error('Network request failed') }),
    /Cannot reach/,
  );
  assert.equal(requireData({ data: null, error: null }), null, 'empty optional rows remain empty');
});
