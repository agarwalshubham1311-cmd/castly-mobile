/** One participant channel plus ref-counted focused conversation channels. Events only invalidate HTTP reads. */
export type LiveTopic = 'bookings' | 'conversations' | 'messages' | 'notifications';
type Payload = {
  table?: string;
  eventType?: string;
  commit_timestamp?: string;
  new?: Record<string, unknown>;
};
type Channel = {
  on: (
    kind: 'postgres_changes',
    filter: { event: 'INSERT' | 'UPDATE'; schema: string; table: string; filter: string },
    callback: (event: Payload) => void,
  ) => Channel;
  subscribe: (callback: (status: string) => void) => Channel;
};
export type LivePort = {
  realtime: { setAuth: (token: string) => Promise<unknown> };
  channel: (name: string) => Channel;
  removeChannel: (channel: Channel) => PromiseLike<unknown>;
};
export class LiveHub {
  private listeners = new Set<{ topic: LiveTopic; conversation?: string; update: () => void }>();
  private base?: Channel;
  private chats = new Map<string, Channel>();
  private timer?: ReturnType<typeof setTimeout>;
  private pending = new Set<string>();
  private seen = new Map<string, number>();
  private active = true;
  private ready = false;
  private notifications = false;
  private status = 'Connecting live updates…';
  private client: LivePort;
  private subject: string;
  private role: string | null;
  private token: string;
  private changed: (status: string) => void;
  private baseGeneration = 0;
  constructor(
    client: LivePort,
    subject: string,
    role: string | null,
    token: string,
    changed: (status: string) => void,
  ) {
    this.client = client;
    this.subject = subject;
    this.role = role;
    this.token = token;
    this.changed = changed;
  }
  async start() {
    try {
      await this.client.realtime.setAuth(this.token);
      if (!this.active) return;
      this.ready = true;
      this.subscribeBase();
      this.syncChats();
    } catch {
      if (this.active) this.setStatus('Live updates unavailable. Refresh to check for changes.');
    }
  }
  private setStatus(status: string) {
    this.status = status;
    this.changed(status);
  }
  getStatus() {
    return this.status;
  }
  private remove(channel: Channel) {
    void Promise.resolve(this.client.removeChannel(channel)).catch(() => {});
  }
  private state = (status: string) => {
    if (!this.active) return;
    this.setStatus(
      status === 'SUBSCRIBED'
        ? 'Live updates connected'
        : 'Live updates unavailable. Refresh to check for changes.',
    );
    if (status === 'SUBSCRIBED') this.poll();
  };
  private event = (payload: Payload) => {
    if (!this.active) return;
    const row = payload.new || {};
    const key = [
      payload.table,
      payload.eventType,
      row.id,
      payload.commit_timestamp,
      row.status,
      row.read_at,
    ].join(':');
    const now = Date.now();
    if (this.seen.has(key) && now - this.seen.get(key)! < 60000) return;
    this.seen.set(key, now);
    if (this.seen.size > 256) this.seen.delete(this.seen.keys().next().value!);
    if (payload.table === 'bookings') {
      this.queue('bookings');
      this.queue('conversations');
      this.queue('messages');
      this.queue('notifications');
    }
    if (payload.table === 'conversations') this.queue('conversations');
    if (payload.table === 'messages') {
      this.queue('messages', String(row.conversation_id || ''));
      this.queue('conversations');
      this.queue('notifications');
    }
    if (payload.table === 'notifications') {
      this.queue('notifications');
      this.queue('conversations');
      if (row.conversation_id) this.queue('messages', String(row.conversation_id));
    }
  };
  private subscribeBase() {
    if (!this.ready || !this.active) return;
    const generation = ++this.baseGeneration;
    if (this.base) this.remove(this.base);
    const filter = `${this.role === 'talent' ? 'talent_id' : 'client_id'}=eq.${this.subject}`;
    const channel = this.client.channel(
      `castly-user-${this.subject}-${Math.random().toString(36).slice(2)}`,
    );
    for (const event of ['INSERT', 'UPDATE'] as const)
      channel.on(
        'postgres_changes',
        { event, schema: 'public', table: 'bookings', filter },
        this.event,
      );
    channel.on(
      'postgres_changes',
      { event: 'INSERT', schema: 'public', table: 'conversations', filter },
      this.event,
    );
    if (this.notifications)
      for (const event of ['INSERT', 'UPDATE'] as const)
        channel.on(
          'postgres_changes',
          {
            event,
            schema: 'public',
            table: 'notifications',
            filter: `recipient_id=eq.${this.subject}`,
          },
          this.event,
        );
    this.base = channel;
    channel.subscribe((status) => {
      if (generation === this.baseGeneration) this.state(status);
    });
  }
  enableNotifications(enabled: boolean) {
    if (this.notifications !== enabled) {
      this.notifications = enabled;
      this.subscribeBase();
    }
  }
  watch(topic: LiveTopic, conversation: string | undefined, update: () => void) {
    const listener = { topic, conversation, update };
    this.listeners.add(listener);
    this.syncChats();
    return () => {
      this.listeners.delete(listener);
      this.syncChats();
    };
  }
  private syncChats() {
    if (!this.ready || !this.active) return;
    const ids = new Set(
      [...this.listeners]
        .filter((l) => l.topic === 'messages' && l.conversation)
        .map((l) => l.conversation!),
    );
    for (const [id, channel] of this.chats)
      if (!ids.has(id)) {
        this.chats.delete(id);
        this.remove(channel);
      }
    for (const id of ids)
      if (!this.chats.has(id)) {
        const channel = this.client.channel(`castly-chat-${id}-${this.subject}`);
        channel
          .on(
            'postgres_changes',
            {
              event: 'INSERT',
              schema: 'public',
              table: 'messages',
              filter: `conversation_id=eq.${id}`,
            },
            (event) => {
              if (this.chats.get(id) === channel) this.event(event);
            },
          )
          .subscribe((status) => {
            if (this.chats.get(id) === channel) this.state(status);
          });
        this.chats.set(id, channel);
      }
  }
  private queue(topic: LiveTopic, conversation?: string) {
    this.pending.add(topic + ':' + (conversation || '*'));
    if (!this.timer) this.timer = setTimeout(() => this.flush(), 180);
  }
  flush() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (!this.active) return;
    const pending = this.pending;
    this.pending = new Set();
    for (const listener of this.listeners)
      if (
        pending.has(listener.topic + ':*') ||
        pending.has(listener.topic + ':' + listener.conversation)
      )
        listener.update();
  }
  poll() {
    if (this.active)
      for (const listener of this.listeners) this.queue(listener.topic, listener.conversation);
  }
  dispose() {
    this.active = false;
    if (this.timer) clearTimeout(this.timer);
    this.pending.clear();
    this.listeners.clear();
    if (this.base) this.remove(this.base);
    for (const channel of this.chats.values()) this.remove(channel);
    this.chats.clear();
  }
}
