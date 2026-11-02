import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { SupabaseClient } from '@supabase/supabase-js';
import type { Database, TalentProfile } from '../src/backend/types.ts';
import {
  createApiTransport,
  createApiServices,
  ApiError,
  selectBusinessMode,
} from '../src/backend/api.ts';
import { composeServices } from '../src/backend/composition.ts';
import { createPlatformServices } from '../src/backend/platform.ts';
import { createServices } from '../src/backend/services.ts';
import {
  collectPages,
  loadPagesThrough,
  mapTalent,
  supportedCategories,
} from '../src/backend/catalog.ts';
import { createSessionTokenProvider } from '../src/backend/tokens.ts';
import { sessionExpired, onSessionExpired } from '../src/backend/sessionEvents.ts';
const token = (sub = 'current') => `e30.${btoa(JSON.stringify({ sub }))}.fixture`;
const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

test('Spring composition never constructs or accesses Supabase business services, including errors', async () => {
  let platformCalls = 0,
    http = 0;
  const db = {
    from() {
      throw new Error('Forbidden direct database access');
    },
    rpc() {
      throw new Error('Forbidden direct RPC');
    },
    auth: {
      signInWithPassword: async () => {
        platformCalls++;
        return { data: {}, error: null };
      },
    },
    storage: {
      from: () => ({
        createSignedUrl: async () => {
          platformCalls++;
          return { data: { signedUrl: 'https://media.example.test/photo' }, error: null };
        },
      }),
    },
  } as unknown as SupabaseClient<Database>;
  const api = createApiServices(
    createApiTransport(
      'https://api.example.test',
      async () => token(),
      async () => {
        http++;
        return reply({ detail: 'Unavailable' }, 503);
      },
    ),
  );
  const services = composeServices('spring', api, createPlatformServices(db), () => {
    throw new Error('Legacy factory must never run');
  });
  await services.auth.login('user@example.test', 'fixture password');
  await services.media.signedUrl('own/photo.jpg');
  await assert.rejects(
    () => services.account.rename('current', 'Name'),
    (e: unknown) => e instanceof ApiError && e.status === 503,
  );
  await assert.rejects(
    () =>
      services.activity.createBooking({
        talent_id: 't',
        project_type: 'Test',
        proposed_date: '2026-12-01',
        location: 'Mumbai',
        requirements: 'Detailed request',
        indicative_budget: 1000,
      }),
    /Unavailable/,
  );
  assert.equal(http, 2);
  assert.equal(platformCalls, 2);
  let factory = 0;
  const legacy = composeServices('supabase', api, {}, () => {
    factory++;
    return createServices(db);
  });
  assert.equal(factory, 1);
  await assert.rejects(() => legacy.activity.transition('id', 'accept'), /requires Spring/);
  assert.equal(http, 2);
});

test('public discovery/categories/profile do not depend on or transmit an expired account token', async () => {
  const urls: string[] = [];
  const api = createApiServices(
    createApiTransport(
      'https://api.example.test',
      async () => {
        throw new Error('Auth must not be consulted');
      },
      async (url, init) => {
        urls.push(String(url));
        assert.equal((init!.headers as Record<string, string>).Authorization, undefined);
        return reply([]);
      },
    ),
  );
  await api.talent.published({
    query: 'brand & art',
    city: 'New Delhi',
    available: true,
    minRate: 1000,
    maxRate: 30000,
    category: 'actors',
    limit: 200,
    offset: 200,
  });
  await api.talent.categories();
  await api.talent.get('public-id');
  await api.portfolio.published(200);
  await api.portfolio.list('public-id', true);
  const url = new URL(urls[0]);
  assert.equal(url.searchParams.get('query'), 'brand & art');
  assert.equal(url.searchParams.get('city'), 'New Delhi');
  assert.equal(url.searchParams.get('offset'), '200');
  assert.equal(url.searchParams.get('available'), 'true');
  assert.equal(url.searchParams.get('category'), 'actors');
  assert.equal(new URL(urls[3]).searchParams.get('offset'), '200');
});

test('terminal 401 expires only its subject; 403/conflict/offline are explicit and never retried', async () => {
  for (const status of [401, 403, 409, 503]) {
    let calls = 0;
    const expired: (string | null)[] = [];
    const request = createApiTransport(
      'https://api.example.test',
      async () => token('own'),
      async () => {
        calls++;
        return reply({}, status);
      },
      false,
      (id) => expired.push(id),
    );
    await assert.rejects(
      () => request('account', 'PATCH', { display_name: 'Name' }),
      (e: unknown) => e instanceof ApiError && e.status === status,
    );
    assert.equal(calls, status === 401 ? 2 : 1);
    assert.deepEqual(expired, status === 401 ? ['own'] : []);
  }
  let calls = 0;
  const request = createApiTransport(
    'https://api.example.test',
    async () => token(),
    async () => {
      calls++;
      throw new Error('offline');
    },
  );
  await assert.rejects(() => request('bookings', 'POST', {}), /Changes have not been confirmed/);
  assert.equal(calls, 1);
});

test('refresh rejection expires the request subject while Auth outages do not log a user out', async () => {
  for (const authError of [new ApiError(401, 'Expired'), new Error('network unavailable')]) {
    const expired: (string | null)[] = [];
    const request = createApiTransport(
      'https://api.example.test',
      async (refresh) => {
        if (refresh) throw authError;
        return token('old-user');
      },
      async () => reply({}, 401),
      false,
      (id) => expired.push(id),
    );
    await assert.rejects(() => request('account'));
    assert.deepEqual(expired, authError instanceof ApiError ? ['old-user'] : []);
  }
});

test('late successful private response is discarded after account switch without expiring the new account', async () => {
  let current = 'first';
  const expired: unknown[] = [];
  const request = createApiTransport(
    'https://api.example.test',
    async () => token(current),
    async () => {
      current = 'second';
      return reply({ private: 'first account' });
    },
    false,
    (id) => expired.push(id),
  );
  await assert.rejects(() => request('account'), /account changed/);
  assert.deepEqual(expired, []);
});

test('session refresh rotation is shared across simultaneous 401s', async () => {
  let calls = 0;
  let finish!: (value: unknown) => void;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  const auth = {
    getSession: async () => ({ data: { session: { access_token: 'old' } }, error: null }),
    refreshSession: () => {
      calls++;
      return pending;
    },
  } as unknown as Parameters<typeof createSessionTokenProvider>[0];
  const get = createSessionTokenProvider(auth);
  const first = get(true),
    second = get(true);
  finish({ data: { session: { access_token: 'fresh' } }, error: null });
  assert.deepEqual(await Promise.all([first, second]), ['fresh', 'fresh']);
  assert.equal(calls, 1);
});

test('catalog pagination traverses beyond the first page, deduplicates and fails on nonadvancing/truncated results', async () => {
  const source = Array.from({ length: 401 }, (_, n) => ({ id: String(n) }));
  const offsets: number[] = [];
  const rows = await collectPages(async (offset) => {
    offsets.push(offset);
    return source.slice(offset, offset + 200);
  });
  assert.equal(rows.length, 401);
  assert.deepEqual(offsets, [0, 200, 400]);
  await assert.rejects(
    () => collectPages(async () => [{ id: '1' }, { id: '2' }], 2),
    /did not advance/,
  );
  await assert.rejects(
    () =>
      collectPages(async (offset) => [{ id: String(offset) }, { id: String(offset + 1) }], 2, 2),
    /too large/,
  );
  assert.deepEqual(await collectPages(async () => []), []);
});

test('refreshing a paginated activity list fetches every loaded page and replaces stale statuses', async () => {
  const offsets: number[] = [];
  const rows = await loadPagesThrough(
    async (offset) => {
      offsets.push(offset);
      return offset
        ? [{ id: 'c', status: 'accepted' }]
        : [
            { id: 'a', status: 'cancelled' },
            { id: 'b', status: 'requested' },
          ];
    },
    2,
    2,
  );
  assert.deepEqual(offsets, [0, 2]);
  assert.equal(rows.rows[0].status, 'cancelled');
  assert.equal(rows.rows.length, 3);
  assert.equal(rows.hasMore, false);
});

test('a full last page retains Load more even when concurrent offset pages overlap', async () => {
  const window = await loadPagesThrough(
    async (offset) => (offset ? [{ id: 'b' }, { id: 'c' }] : [{ id: 'a' }, { id: 'b' }]),
    2,
    2,
  );
  assert.equal(window.rows.length, 3);
  assert.equal(window.hasMore, true);
});

test('snake_case talent metadata maps to UI without invented claims; one failed image does not hide talent', async () => {
  const profile = {
    id: 't',
    display_name: 'Talent',
    category_id: 'actors',
    city: 'Mumbai',
    day_rate: 18000,
    bio: 'Bio',
    skills: ['Acting'],
    available: true,
    published: true,
    portrait_path: 't/p.jpg',
  } as TalentProfile;
  const result = await mapTalent(
    profile,
    [
      {
        id: 'a',
        talent_id: 't',
        path: 't/a.jpg',
        label: 'Editorial',
        position: 0,
        created_at: '',
        media_type: 'image',
      },
    ],
    async (path) => {
      if (path.endsWith('p.jpg')) throw new Error('Storage unavailable');
      return 'https://media.example.test/a';
    },
  );
  assert.equal(result.talent.name, 'Talent');
  assert.equal(result.talent.rate, 18000);
  assert.equal(result.talent.category, 'Actors');
  assert.equal(result.mediaUnavailable, true);
  assert.equal(result.talent.portfolio[0].label, 'Editorial');
  assert.equal('verified' in result.talent, false);
  assert.deepEqual(
    supportedCategories([
      { id: 'models', name: 'Models' },
      { id: 'unsupported', name: 'Other' },
    ]),
    ['All talent', 'Models'],
  );
});

test('booking DTO excludes protected fields, portfolio edit and message cursor match Spring contracts', async () => {
  const seen: { path: string; body: unknown }[] = [];
  const api = createApiServices(async <T>(path: string, _method = 'GET', body?: unknown) => {
    seen.push({ path, body });
    return {} as T;
  });
  await api.activity.createBooking({
    talent_id: 't',
    project_type: 'Shoot',
    proposed_date: '2026-12-01',
    location: 'Mumbai',
    requirements: 'Detailed brief',
    indicative_budget: 18000,
    client_id: 'forged',
    status: 'accepted',
    agreed_rate: 0,
  } as Parameters<typeof api.activity.createBooking>[0]);
  assert.equal('client_id' in (seen[0].body as object), false);
  assert.equal('status' in (seen[0].body as object), false);
  assert.equal('agreed_rate' in (seen[0].body as object), false);
  await api.portfolio.edit('asset', 'Caption', 2);
  assert.deepEqual(seen[1], { path: 'portfolio/asset', body: { label: 'Caption', position: 2 } });
  await api.activity.messages('c', 'before-id');
  assert.equal(seen[2].path, 'conversations/c/messages?limit=50&before=before-id');
  await api.activity.bookings(100);
  assert.equal(seen[3].path, 'bookings?limit=100&offset=100');
  await api.activity.openConversation('b');
  assert.deepEqual(seen[4].body, { booking_id: 'b' });
});

test('device URLs and isolated fallback are explicit, with no credential-bearing API URLs', () => {
  for (const url of [
    'http://10.0.2.2:8080',
    'http://localhost:8080',
    'https://api.example.test',
    'http://192.168.1.20:8080',
  ])
    assert.doesNotThrow(() => createApiTransport(url, async () => null, fetch, true));
  for (const url of [
    'https://user:password@api.example.test',
    'https://api.example.test?key=secret',
    'http://192.168.1.20:8080',
  ])
    assert.throws(() => createApiTransport(url, async () => null));
  assert.throws(() => selectBusinessMode('supabase', true), /isolated/);
  assert.throws(() => selectBusinessMode('supabase', false, true), /development-only/);
  assert.equal(selectBusinessMode('supabase', true, true), 'supabase');
});

test('session notice subscription carries only subject identity and cleans up', () => {
  const received: unknown[] = [];
  const off = onSessionExpired((id) => received.push(id));
  sessionExpired('own');
  off();
  sessionExpired('other');
  assert.deepEqual(received, ['own']);
});

test('a form bound to another user cannot send its private write after the session changes', async () => {
  let sent = 0;
  const expired: unknown[] = [];
  const api = createApiServices(
    createApiTransport(
      'https://api.example.test',
      async () => token('new-user'),
      async () => {
        sent++;
        return reply({});
      },
      false,
      (id) => expired.push(id),
    ),
  );
  await assert.rejects(
    () => api.account.rename('original-user', 'Old form name'),
    /account changed/,
  );
  await assert.rejects(
    () => api.activity.sendMessage('conversation', 'Old draft', 'original-user'),
    /account changed/,
  );
  await assert.rejects(
    () =>
      api.activity.createBooking(
        {
          talent_id: 't',
          project_type: 'Shoot',
          location: 'Mumbai',
          requirements: 'Old form request',
          proposed_date: '2026-12-01',
          indicative_budget: 1000,
        },
        'original-user',
      ),
    /account changed/,
  );
  assert.equal(sent, 0);
  assert.deepEqual(expired, []);
});
