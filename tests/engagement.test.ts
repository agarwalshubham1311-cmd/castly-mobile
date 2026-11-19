import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deliveryId, deliveryPathId, safeNotificationRoute, defaultPreferences, preferenceInput } from '../src/engagement/types.ts';
import { createApiTransport, createApiServices } from '../src/backend/api.ts';
import { beforeLogout, cleanupBeforeLogout } from '../src/engagement/logout.ts';
test('engagement defaults are opt-out and device payload cannot supply a trusted route', () => {
  assert.equal(Object.values(defaultPreferences).filter((v) => typeof v === 'boolean').some(Boolean), false);
  const id = '60000000-0000-0000-0000-000000000001';
  assert.equal(deliveryId({ delivery_id: id, route: 'https://attacker.invalid' }), id);
  assert.equal(deliveryPathId('/engagement/open/' + id), id);
  assert.equal(deliveryPathId('https://attacker.invalid/engagement/open/' + id), null);
  assert.equal(deliveryPathId('/chat/' + id), null);
  assert.equal(deliveryId({ delivery_id: 'bad', route: '/chat/private' }), null);
  assert.equal(safeNotificationRoute('/booking-detail/' + id), true);
  assert.equal(safeNotificationRoute('/chat/' + id), true);
  for (const route of ['https://attacker.invalid', '/admin', '/chat/not-uuid', '/chat/' + id + '?token=private']) assert.equal(safeNotificationRoute(route), false);
});
test('engagement transport rejects prior-account writes and removes server-managed preference keys', async () => {
  let sends = 0;
  const token = 'x.' + Buffer.from(JSON.stringify({ sub: 'new-account' })).toString('base64url') + '.x';
  const api = createApiServices(createApiTransport('https://api.fixture.invalid', async () => token, async () => { sends++; return new Response('{}'); }));
  await assert.rejects(api.engagement.update('old-account', defaultPreferences));
  await assert.rejects(api.engagement.register('old-account', 'device', 'private-token', 'android'));
  await assert.rejects(api.engagement.open('old-account', 'delivery'));
  await assert.rejects(api.engagement.results('old-account', 'saved-search'));
  assert.equal(sends, 0);
  const input = preferenceInput({ ...defaultPreferences, user_id: 'forged', revision: 99 } as typeof defaultPreferences);
  assert.equal('user_id' in input, false); assert.equal('revision' in input, false);
});
test('logout device cleanup runs for the departing actor and cannot block logout on failure', async () => {
  const seen: string[] = [];
  const a = beforeLogout(async (actor) => { seen.push(actor); });
  const b = beforeLogout(async () => { throw new Error('offline'); });
  try { await cleanupBeforeLogout('departing-account'); assert.deepEqual(seen, ['departing-account']); } finally { a(); b(); }
});

test('Phase 6 secrets cannot be exposed as Expo public variables', async () => {
  const { checkClientEnvironment } = await import('../scripts/check-client-env.cjs');
  for (const name of ['EXPO_PUBLIC_PHASE6_EMAIL_KEY', 'EXPO_PUBLIC_PHASE6_WORKER_DB_PASSWORD', 'EXPO_PUBLIC_PHASE6_ANALYTICS_KEY', 'EXPO_PUBLIC_PHASE6_UNSUBSCRIBE_KEY', 'EXPO_PUBLIC_PHASE6_WEBHOOK_SECRET']) assert.throws(() => checkClientEnvironment({ NODE_ENV: 'test', [name]: 'secret-fixture' }, '/tmp/phase6-empty-root'));
  assert.doesNotThrow(() => checkClientEnvironment({ NODE_ENV: 'test', EXPO_PUBLIC_PUSH_ENABLED: 'false' }, '/tmp/phase6-empty-root'));
});
