import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApiServices, createApiTransport } from '../src/backend/api.ts';
const actor = '00000000-0000-0000-0000-000000000001';
const token = () =>
  `e30.${Buffer.from(JSON.stringify({ sub: actor, exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.signature`;
test('trust writes preserve expected actor and bounded target payloads', async () => {
  const calls: { url: string; method: string; body: unknown }[] = [];
  const api = createApiServices(
    createApiTransport(
      'https://api.example.test',
      async () => token(),
      async (url, init) => {
        calls.push({
          url: String(url),
          method: init?.method || 'GET',
          body: init?.body ? JSON.parse(String(init.body)) : null,
        });
        return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
      },
    ),
  );
  await api.trust.report('message', 'content/id', 'harassment', 'A specific incident', actor);
  assert.match(calls[0].url, /\/reports$/);
  assert.deepEqual(calls[0].body, {
    target_kind: 'message',
    target_id: 'content/id',
    category: 'harassment',
    description: 'A specific incident',
  });
  await assert.rejects(api.trust.block('someone', true, 'different-account'));
  assert.equal(calls.length, 1);
  await api.trust.confirmCompletion('booking/id', actor);
  assert.match(calls[1].url, /booking%2Fid\/completion$/);
});
