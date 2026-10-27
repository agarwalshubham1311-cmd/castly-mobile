import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createApiTransport,
  createApiServices,
  ApiError,
  selectBusinessMode,
} from '../src/backend/api.ts';
import { mergeMessages, messageWindow } from '../src/backend/messages.ts';
const response = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
test('API attaches verified session token and refreshes once after 401', async () => {
  const headers: string[] = [];
  const refresh: boolean[] = [];
  const request = createApiTransport(
    'https://api.example.test',
    async (force = false) => {
      refresh.push(force);
      return force ? 'fresh' : 'old';
    },
    async (_url, init) => {
      headers.push((init!.headers as Record<string, string>).Authorization);
      return headers.length === 1 ? response({}, 401) : response({ id: 'own' });
    },
  );
  assert.deepEqual(await request('account'), { id: 'own' });
  assert.deepEqual(headers, ['Bearer old', 'Bearer fresh']);
  assert.deepEqual(refresh, [false, true]);
});
test('API does not retry failed writes or silently fall back, and exposes server errors', async () => {
  let calls = 0;
  const request = createApiTransport(
    'https://api.example.test',
    async () => null,
    async () => {
      calls++;
      return response({ detail: 'Booking is no longer eligible' }, 409);
    },
  );
  await assert.rejects(
    () => request('bookings/id/accept', 'POST'),
    (e: unknown) => e instanceof ApiError && e.status === 409,
  );
  assert.equal(calls, 1);
  const offline = createApiTransport(
    'https://api.example.test',
    async () => null,
    async () => {
      throw new TypeError('fetch failed');
    },
  );
  await assert.rejects(() => offline('account'), /Changes have not been confirmed/);
});
test('typed business service excludes caller identities and protected talent fields', async () => {
  const captured: { path: string; method: string; body: unknown }[] = [];
  const services = createApiServices(async <T>(path: string, method = 'GET', body?: unknown) => {
    captured.push({ path, method, body });
    return {} as T;
  });
  await services.saved.add('forged-user', 'talent');
  await services.account.get('forged-user');
  await services.activity.sendMessage('conversation', ' hello ');
  await services.talent.update('forged-user', {
    id: 'forged',
    created_at: 'forged',
    display_name: 'Name',
    category_id: 'actors',
    city: 'Mumbai',
    day_rate: 100,
    bio: 'Bio',
    skills: [],
    available: true,
    published: false,
    portrait_path: null,
  } as Parameters<typeof services.talent.update>[1]);
  assert.deepEqual(captured[0], { path: 'favourites/talent', method: 'PUT', body: undefined });
  assert.equal(captured[1].path, 'account');
  assert.deepEqual(captured[2].body, { body: 'hello' });
  assert.equal('id' in (captured[3].body as object), false);
});
test('missing own profile is empty while other API failures remain errors', async () => {
  const notFound = createApiServices(async () => {
    throw new ApiError(404, 'Not found');
  });
  assert.equal(await notFound.talent.own('me'), null);
  const unavailable = createApiServices(async () => {
    throw new ApiError(503, 'Unavailable');
  });
  await assert.rejects(() => unavailable.talent.own('me'), /Unavailable/);
});
test('message history merges duplicate POST/Realtime results in precise server order', () => {
  const row = (id: string, created_at: string) => ({
    id,
    created_at,
    conversation_id: 'c',
    sender_id: 'u',
    body: id,
  });
  const a = row('z', '2026-10-27T10:00:00Z'),
    b = row('a', '2026-10-27T10:00:00.000001Z'),
    c = row('c', '2026-10-27T10:00:00.100Z');
  assert.deepEqual(
    mergeMessages([c, a], [b, c]).map((row) => row.id),
    ['z', 'a', 'c'],
  );
});

test('direct database fallback is opt-in and impossible in production mode', () => {
  assert.equal(selectBusinessMode(undefined, false), 'spring');
  assert.equal(selectBusinessMode('supabase', true), 'supabase');
  assert.throws(() => selectBusinessMode('supabase', false), /development-only/);
  assert.throws(() => selectBusinessMode('invalid', true), /development-only/);
});

test('a refreshed token cannot retry a request under a different account', async () => {
  let calls = 0;
  const jwt = (sub: string) => `e30.${btoa(JSON.stringify({ sub }))}.test`;
  const request = createApiTransport(
    'https://api.example.test',
    async (refresh) => jwt(refresh ? 'other' : 'original'),
    async () => {
      calls++;
      return response({}, 401);
    },
  );
  await assert.rejects(() => request('bookings', 'POST', {}), /account changed/);
  assert.equal(calls, 1);
});
test('history recovery never joins stale pages across an unseen reconnect gap', () => {
  const row = (id: string) => ({
    id,
    created_at: '2026-10-27T10:00:00Z',
    conversation_id: 'c',
    sender_id: 'u',
    body: id,
  });
  const history = [row('old')],
    latest = [row('new1'), row('new2')];
  assert.deepEqual(messageWindow(history, latest, 2), { rows: latest, reset: true });
  assert.equal(messageWindow(history, [row('old'), row('new')], 2).reset, false);
});
