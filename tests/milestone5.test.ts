import { test } from 'node:test';
import assert from 'node:assert/strict';
import { LiveHub, type LivePort } from '../src/backend/liveHub.ts';
import { chatScrollTarget, isNearBottom } from '../src/backend/chatScroll.ts';
import { notificationRoute, mergeNotifications } from '../src/backend/notifications.ts';
import { createApiTransport, createApiServices } from '../src/backend/api.ts';
import type { NotificationRow } from '../src/backend/types.ts';
function fixture() {
  const channels: {
    name: string;
    handlers: { filter: Record<string, string>; callback: (e: any) => void }[];
    state?: (s: string) => void;
    on: any;
    subscribe: any;
  }[] = [];
  const removed: unknown[] = [];
  let auth = 0;
  const port = {
    realtime: {
      setAuth: async () => {
        auth++;
      },
    },
    channel(name: string) {
      const channel: any = {
        name,
        handlers: [],
        on(_kind: string, filter: Record<string, string>, callback: (e: any) => void) {
          channel.handlers.push({ filter, callback });
          return channel;
        },
        subscribe(callback: (s: string) => void) {
          channel.state = callback;
          return channel;
        },
      };
      channels.push(channel);
      return channel;
    },
    removeChannel: async (channel: unknown) => {
      removed.push(channel);
    },
  } as LivePort;
  return { port, channels, removed, auth: () => auth };
}
test('one authorized base channel, reference-counted chat, deduplicated events and cleanup/reconnect', async () => {
  const fake = fixture();
  const statuses: string[] = [];
  const hub = new LiveHub(fake.port, 'own-user', 'talent', 'in-memory-test-token', (s) =>
    statuses.push(s),
  );
  let updates = 0;
  const off = hub.watch('messages', 'conversation', () => updates++);
  const second = hub.watch('messages', 'conversation', () => updates++);
  await hub.start();
  assert.equal(fake.auth(), 1);
  assert.equal(fake.channels.length, 2);
  assert.ok(fake.channels[0].handlers.every((h) => h.filter.filter === 'talent_id=eq.own-user'));
  assert.equal(fake.channels[1].handlers[0].filter.filter, 'conversation_id=eq.conversation');
  const event = {
    table: 'messages',
    eventType: 'INSERT',
    commit_timestamp: 'one',
    new: { id: 'message', conversation_id: 'conversation' },
  };
  fake.channels[1].handlers[0].callback(event);
  fake.channels[1].handlers[0].callback(event);
  hub.flush();
  assert.equal(updates, 2);
  off();
  assert.equal(fake.removed.length, 0);
  second();
  assert.equal(fake.removed.length, 1);
  hub.enableNotifications(true);
  assert.ok(
    fake.channels.at(-1)!.handlers.some((h) => h.filter.filter === 'recipient_id=eq.own-user'),
  );
  const notify = hub.watch('notifications', undefined, () => updates++);
  fake.channels.at(-1)!.state!('SUBSCRIBED');
  hub.flush();
  assert.equal(updates, 3);
  notify();
  hub.dispose();
  const previous = updates;
  fake.channels[1].handlers[0].callback(event);
  hub.flush();
  assert.equal(updates, previous);
  assert.ok(statuses.includes('Live updates connected'));
});
test('booking and notification events in the same burst coalesce; unrelated chat events do not update it', async () => {
  const fake = fixture();
  const hub = new LiveHub(fake.port, 'client', 'client', 'fixture', () => {});
  let updates = 0;
  hub.watch('messages', 'one', () => updates++);
  await hub.start();
  const base = fake.channels[0];
  base.handlers[1].callback({
    table: 'bookings',
    eventType: 'UPDATE',
    new: { id: 'b', status: 'accepted' },
  });
  base.handlers[1].callback({
    table: 'bookings',
    eventType: 'UPDATE',
    new: { id: 'b', status: 'accepted' },
  });
  hub.flush();
  assert.equal(updates, 1);
  fake.channels[1].handlers[0].callback({
    table: 'messages',
    eventType: 'INSERT',
    new: { id: 'other', conversation_id: 'two' },
  });
  hub.flush();
  assert.equal(updates, 1);
  hub.dispose();
});
test('notification navigation/dedup and DTOs are recipient-owned API operations', async () => {
  const row = {
    id: 'n',
    kind: 'message_received',
    conversation_id: 'c',
    booking_id: null,
    created_at: '2026-11-02T00:00:00Z',
    read_at: null,
  } as NotificationRow;
  assert.equal(notificationRoute(row), '/chat/c');
  assert.equal(
    notificationRoute({ ...row, kind: 'booking_accepted', booking_id: 'b' }),
    '/booking-detail/b',
  );
  assert.equal(mergeNotifications([row], [{ ...row, read_at: 'now' }]).length, 1);
  assert.equal(mergeNotifications([row], [{ ...row, read_at: 'now' }])[0].read_at, 'now');
  const calls: any[] = [];
  const services = createApiServices(async <T>(path: string, method = 'GET', body?: unknown) => {
    calls.push({ path, method, body });
    return {} as T;
  });
  await services.notifications.read('n', 'owner');
  await services.notifications.readConversation('c', 'owner');
  await services.notifications.list('cursor');
  assert.deepEqual(calls[0], { path: 'notifications/n/read', method: 'POST', body: undefined });
  assert.equal(calls[1].path, 'conversations/c/notifications/read');
  assert.equal(calls[2].path, 'notifications?limit=50&before=cursor');
});
test('chat scrolling preserves older-page position and avoids jumping while reading history', () => {
  assert.equal(isNearBottom(850, 100, 1000), true);
  assert.equal(isNearBottom(200, 100, 1000), false);
  assert.deepEqual(chatScrollTarget(1000, 1500, 200, true, false, false), {
    kind: 'offset',
    y: 700,
  });
  assert.deepEqual(chatScrollTarget(1000, 1100, 200, false, false, false), { kind: 'stay' });
  assert.deepEqual(chatScrollTarget(1000, 1100, 200, false, false, true), { kind: 'end' });
});

test('Realtime auth failure falls back safely and disposal before auth resolves creates no channels', async () => {
  const failed = fixture();
  failed.port.realtime.setAuth = async () => {
    throw new Error('offline');
  };
  const states: string[] = [];
  const fallback = new LiveHub(failed.port, 'own', 'client', 'fixture', (s) => states.push(s));
  await fallback.start();
  assert.equal(failed.channels.length, 0);
  assert.ok(states[0].includes('unavailable'));
  fallback.dispose();
  const delayed = fixture();
  let finish!: () => void;
  delayed.port.realtime.setAuth = () =>
    new Promise<void>((resolve) => {
      finish = resolve;
    });
  const disposed = new LiveHub(delayed.port, 'own', 'talent', 'fixture', () => {});
  const starting = disposed.start();
  disposed.dispose();
  finish();
  await starting;
  assert.equal(delayed.channels.length, 0);
});

test('database denials expose a safe support reference without SQL or credentials', async () => {
  const reference = '11111111-1111-4111-8111-111111111111';
  const request = createApiTransport(
    'https://api.example.test',
    async () => null,
    async () =>
      new Response(
        JSON.stringify({ detail: 'Not permitted', reference, operation: 'conversation.insert' }),
        { status: 403, headers: { 'Content-Type': 'application/json' } },
      ),
  );
  await assert.rejects(
    () => request('bookings/id/accept', 'POST'),
    (error: unknown) =>
      error instanceof Error &&
      error.message.includes(reference) &&
      !error.message.includes('conversation.insert'),
  );
});

test('notification page merging preserves descending PostgreSQL microsecond order', () => {
  const row = (id: string, created_at: string) => ({ id, created_at }) as NotificationRow;
  const whole = row('z', '2026-11-02T10:00:00Z'),
    micro = row('a', '2026-11-02T10:00:00.000001Z'),
    later = row('b', '2026-11-02T10:00:00.100Z');
  assert.deepEqual(
    mergeNotifications([whole], [micro, later, whole]).map((n) => n.id),
    ['b', 'a', 'z'],
  );
});
