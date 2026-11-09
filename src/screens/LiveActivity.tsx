import { TrustLinks } from '../trust/TrustLinks';
import { DecisionDialog, type Decision } from '../components/DecisionDialog';
import { BookingStatus } from '../components/BookingStatus';
import { useNotifications } from '../backend/NotificationProvider';
import { isNearBottom, chatScrollTarget } from '../backend/chatScroll';
import { loadPagesThrough } from '../backend/catalog';
import { ApiError } from '../backend/api';
import { usePublishedTalent } from '../backend/usePublishedTalent';
import { mergeMessages, messageWindow } from '../backend/messages';
import React, { useCallback, useEffect, useState, useRef } from 'react';
import { View, Text, TextInput, ScrollView, type ScrollViewInstance } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { readableError } from '../backend/errors';
import { useLiveRefresh } from '../backend/useLiveRefresh';
import type { BookingRow, MessageRow } from '../backend/types';
import { localDateISO } from '../dates';
import DateField from '../components/DateField';
import { validateBooking, type BookingForm } from '../demo';
import { c, f } from '../theme';
import {
  Heading,
  Label,
  PrimaryButton,
  Screen,
  EmptyState,
  Field,
  CategoryChip,
  Chips,
} from '../components/ui';
function useResource<T>(load: () => Promise<T>, revision: number) {
  const [snapshot, setSnapshot] = useState<{ load: typeof load; data: T | null } | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [local, setLocal] = useState(0);
  useEffect(() => {
    let active = true;
    load()
      .then((value) => {
        if (active) {
          setSnapshot({ load, data: value });
          setError('');
        }
      })
      .catch((e) => {
        if (active) {
          setSnapshot({ load, data: null });
          setError(readableError(e));
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [load, revision, local]);
  return {
    data: snapshot?.load === load ? snapshot.data : null,
    error: snapshot?.load === load ? error : '',
    loading: loading || snapshot?.load !== load,
    refresh: () => {
      setLoading(true);
      setLocal((value) => value + 1);
    },
  };
}
function Feedback({
  loading,
  error,
  retry,
}: {
  loading: boolean;
  error: string;
  retry: () => void;
}) {
  return (
    <>
      {loading && <Label accessibilityLiveRegion="polite">Loading…</Label>}
      {!!error && (
        <>
          <Label accessibilityRole="alert" style={{ color: c.error }}>
            {error}
          </Label>
          <PrimaryButton title="Retry" secondary onPress={retry} />
        </>
      )}
    </>
  );
}
const panel = {
  backgroundColor: c.white,
  borderRadius: 16,
  padding: 16,
  gap: 10,
  borderColor: c.border,
  borderWidth: 1,
} as const;
function BookingSummary({ booking }: { booking: BookingRow }) {
  const auth = useAuth();
  return (
    <View style={panel}>
      <Heading>
        {auth.session?.user.id === booking.client_id
          ? booking.talent_name || 'Talent'
          : booking.client_name || 'Client'}
      </Heading>
      <Label style={{ color: c.text }}>{booking.project_type}</Label>
      <Label>
        {booking.proposed_date} · {booking.location}
      </Label>
      <Label>₹{booking.indicative_budget.toLocaleString('en-IN')} indicative budget</Label>
      <BookingStatus status={booking.status} />
    </View>
  );
}
export function LiveBookings() {
  const auth = useAuth();
  const live = useLiveRefresh('bookings');
  const [tab, setTab] = useState('All');
  const [offset, setOffset] = useState(0);
  const load = useCallback(
    () => loadPagesThrough((at) => services!.activity.bookings(at), offset),
    [offset],
  );
  const state = useResource(load, live.revision);
  const rows = state.data?.rows || [];
  return (
    <Screen title="Bookings" back={false}>
      <Label>
        {auth.account?.primary_role === 'talent'
          ? 'Incoming requests and booking history'
          : 'Your submitted requests and booking history'}
      </Label>
      <Chips>
        {['All', 'requested', 'accepted', 'declined', 'cancelled', 'completed', 'History'].map(
          (value) => (
            <CategoryChip
              key={value}
              title={value === 'All' ? value : value[0].toUpperCase() + value.slice(1)}
              active={tab === value}
              onPress={() => setTab(value)}
            />
          ),
        )}
      </Chips>
      <Label>{live.status}</Label>
      <PrimaryButton
        title="Refresh bookings"
        secondary
        onPress={() => {
          setOffset(0);
          state.refresh();
        }}
      />
      <Feedback {...state} retry={state.refresh} />
      {rows
        .filter(
          (row) =>
            tab === 'All' ||
            (tab === 'History'
              ? ['declined', 'cancelled', 'completed'].includes(row.status)
              : row.status === tab),
        )
        .map((row) => (
          <View key={row.id} style={{ gap: 8 }}>
            <BookingSummary booking={row} />
            <PrimaryButton
              title={`View ${row.project_type} booking`}
              secondary
              onPress={() => router.push(`/booking-detail/${row.id}`)}
            />
          </View>
        ))}
      {!state.loading && !state.error && !rows.length && (
        <EmptyState
          title="No bookings yet"
          detail="Your requests and booking decisions will appear here."
          action="Discover talent"
          onPress={() => router.push('/search')}
        />
      )}
      {state.data?.hasMore && (
        <PrimaryButton
          title="Load more bookings"
          secondary
          onPress={() => {
            setOffset((value) => value + 100);
          }}
        />
      )}
    </Screen>
  );
}
export function LiveBookingDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const auth = useAuth();
  const live = useLiveRefresh('bookings');
  const load = useCallback(() => services!.activity.booking(id), [id]);
  const state = useResource(load, live.revision);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [decision, setDecision] = useState<Decision | null>(null);
  const booking = !state.loading && !state.error ? state.data : null;
  const talent = booking?.talent_id === auth.session?.user.id;
  const client = booking?.client_id === auth.session?.user.id;
  const transition = async (decision: 'accept' | 'reject' | 'cancel') => {
    setBusy(true);
    setError('');
    try {
      await services!.activity.transition(id, decision, auth.session!.user.id);
      setDecision(null);
      state.refresh();
    } catch (e) {
      setError(readableError(e));
      if (e instanceof ApiError && [403, 404, 409].includes(e.status)) state.refresh();
    } finally {
      setBusy(false);
    }
  };
  const open = async () => {
    setBusy(true);
    setError('');
    try {
      const conversation = await services!.activity.openConversation(id, auth.session!.user.id);
      router.push(`/chat/${conversation.id}`);
    } catch (e) {
      setError(readableError(e));
      if (e instanceof ApiError && [403, 404, 409].includes(e.status)) state.refresh();
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title="Booking details">
      <DecisionDialog
        decision={decision}
        busy={busy}
        disabled={!booking || !!state.error || state.loading}
        error={error}
        onClose={() => setDecision(null)}
        onConfirm={() => {
          if (decision && booking) void transition(decision);
        }}
      />
      <Feedback {...state} retry={state.refresh} />
      {booking && (
        <>
          <BookingSummary booking={booking} />
          <TrustLinks bookingId={booking.id} />
          <Heading>Project requirements</Heading>
          <Label>{booking.requirements}</Label>
          <Label>No payment is collected through this booking.</Label>
          {booking.status === 'requested' && talent && (
            <>
              <PrimaryButton
                title="Accept booking"
                disabled={busy}
                onPress={() => {
                  setError('');
                  setDecision('accept');
                }}
              />
              <PrimaryButton
                title="Reject booking"
                secondary
                disabled={busy}
                onPress={() => {
                  setError('');
                  setDecision('reject');
                }}
              />
            </>
          )}
          {((booking.status === 'accepted' && (client || talent)) ||
            (booking.status === 'requested' && client)) && (
            <PrimaryButton
              title="Cancel booking"
              secondary
              disabled={busy}
              onPress={() => {
                setError('');
                setDecision('cancel');
              }}
            />
          )}
          {['accepted', 'completed'].includes(booking.status) && (client || talent) && (
            <PrimaryButton
              title="Open conversation"
              disabled={busy}
              onPress={() => {
                void open();
              }}
            />
          )}
          {!!error && (
            <Label accessibilityRole="alert" style={{ color: c.error }}>
              {error}
            </Label>
          )}
        </>
      )}
    </Screen>
  );
}
export function LiveBookingRequest() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const auth = useAuth();
  const profile = usePublishedTalent(id);
  const { talent } = profile;
  const [form, setForm] = useState<BookingForm>({
    project: '',
    date: '',
    location: '',
    requirements: '',
    budget: '',
  });
  const [errors, setErrors] = useState<Partial<Record<keyof BookingForm, string>>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const update = (key: keyof BookingForm) => (value: string) =>
    setForm((current) => ({ ...current, [key]: value }));
  const submit = async () => {
    const issues = validateBooking(form, localDateISO());
    if (!Number.isInteger(Number(form.budget)) || Number(form.budget) > 10000000)
      issues.budget = 'Enter a whole-rupee budget up to ₹10,000,000.';
    setErrors(issues);
    setError('');
    if (Object.keys(issues).length) return;
    setBusy(true);
    try {
      const booking = await services!.activity.createBooking(
        {
          talent_id: id,
          project_type: form.project.trim(),
          proposed_date: form.date,
          location: form.location.trim(),
          requirements: form.requirements.trim(),
          indicative_budget: Number(form.budget),
        },
        auth.session!.user.id,
      );
      router.replace(`/booking-detail/${booking.id}`);
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  if (auth.account?.primary_role !== 'client')
    return (
      <Screen title="Request booking">
        <EmptyState
          title="Client account required"
          detail="Booking requests can be created by Client accounts."
        />
      </Screen>
    );
  if (profile.loading || profile.error || !talent)
    return (
      <Screen title="Request booking">
        {profile.loading ? (
          <Label>Loading talent…</Label>
        ) : (
          <EmptyState
            title="Talent unavailable"
            detail={profile.error || 'This talent is unpublished or unavailable.'}
            action="Retry talent"
            onPress={profile.retry}
          />
        )}
      </Screen>
    );
  return (
    <Screen
      title="Request booking"
      footer={
        <PrimaryButton
          title={busy ? 'Sending request…' : 'Send booking request'}
          disabled={busy}
          onPress={() => {
            void submit();
          }}
        />
      }
    >
      <Heading>{talent?.name || 'Talent booking'}</Heading>
      <Label>Send your project details to the talent. No payment will be collected.</Label>
      <Field
        label="Project type"
        value={form.project}
        onChangeText={update('project')}
        error={errors.project}
        maxLength={100}
      />
      <DateField value={form.date} onChange={update('date')} error={errors.date} />
      <Field
        label="Location"
        value={form.location}
        onChangeText={update('location')}
        error={errors.location}
        maxLength={200}
      />
      <Field
        label="Project requirements"
        multiline
        value={form.requirements}
        onChangeText={update('requirements')}
        error={errors.requirements}
        maxLength={4000}
      />
      <Field
        label="Indicative budget (INR)"
        keyboardType="numeric"
        value={form.budget}
        onChangeText={update('budget')}
        error={errors.budget}
      />
      {!!error && (
        <Label accessibilityRole="alert" style={{ color: c.error }}>
          {error}
        </Label>
      )}
    </Screen>
  );
}
export function LiveConversations() {
  const auth = useAuth();
  const { talent } = useLocalSearchParams<{ talent?: string }>();
  const live = useLiveRefresh('conversations');
  const [offset, setOffset] = useState(0);
  const load = useCallback(
    () => loadPagesThrough((at) => services!.activity.conversations(at), offset),
    [offset],
  );
  const state = useResource(load, live.revision);
  const rows = (state.data?.rows || []).filter((row) => !talent || row.talent_id === talent);
  return (
    <Screen title="Messages" back={false}>
      <Label>{live.status}</Label>
      <PrimaryButton
        title="Refresh conversations"
        secondary
        onPress={() => {
          setOffset(0);
          state.refresh();
        }}
      />
      <Feedback {...state} retry={state.refresh} />
      {rows.map((row) => (
        <View key={row.id} style={panel}>
          <Heading>
            {row.client_id === auth.session?.user.id
              ? row.talent_name || 'Talent'
              : row.client_name || 'Client'}
          </Heading>
          <Label>
            {row.can_send
              ? 'Continue your conversation'
              : 'Message history · no active accepted booking'}
          </Label>
          {typeof row.unread_notifications === 'number' && row.unread_notifications > 0 && (
            <Label accessibilityLiveRegion="polite">
              {row.unread_notifications} unread message notifications
            </Label>
          )}
          <PrimaryButton
            title="Open conversation"
            secondary
            onPress={() => router.push(`/chat/${row.id}`)}
          />
        </View>
      ))}
      {!state.loading && !state.error && !rows.length && (
        <EmptyState
          title="No conversations yet"
          detail="A conversation becomes available after a booking is accepted."
          action={
            talent && auth.account?.primary_role === 'client' ? 'Request booking' : 'View bookings'
          }
          onPress={() =>
            router.push(
              talent && auth.account?.primary_role === 'client'
                ? `/booking/${talent}`
                : '/bookings',
            )
          }
        />
      )}
      {state.data?.hasMore && (
        <PrimaryButton
          title="Load more conversations"
          secondary
          onPress={() => {
            setOffset((value) => value + 100);
          }}
        />
      )}
    </Screen>
  );
}
export function LiveChat() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <ChatThread key={id} id={id} />;
}
function ChatThread({ id }: { id: string }) {
  const auth = useAuth();
  const { stats: notificationStats, refresh: refreshNotifications } = useNotifications();
  const live = useLiveRefresh('messages', id);
  const scroll = useRef<ScrollViewInstance>(null);
  const height = useRef(0),
    offset = useRef(0),
    prepend = useRef(false),
    forceBottom = useRef(true);
  const [nearBottom, setNearBottom] = useState(true),
    [newMessages, setNewMessages] = useState(false);
  const lastSeen = useRef('');
  const load = useCallback(
    () => Promise.all([services!.activity.conversation(id), services!.activity.messages(id)]),
    [id],
  );
  const state = useResource(load, live.revision);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [history, setHistory] = useState<MessageRow[]>([]);
  const [more, setMore] = useState(true);
  const conversation = !state.loading && !state.error ? state.data?.[0] : null;
  const window = messageWindow(history, state.data?.[1] || []);
  const rows = state.error ? [] : window.rows;
  const send = async () => {
    if (!text.trim() || busy || !conversation?.can_send) return;
    setBusy(true);
    setError('');
    try {
      const message = await services!.activity.sendMessage(id, text, auth.session!.user.id);
      forceBottom.current = true;
      setHistory(mergeMessages(rows, [message]));
      setText('');
      state.refresh();
    } catch (e) {
      setError(readableError(e));
      if (e instanceof ApiError && [403, 404, 409].includes(e.status)) state.refresh();
    } finally {
      setBusy(false);
    }
  };
  const older = async () => {
    if (!rows.length) return;
    setBusy(true);
    setError('');
    try {
      prepend.current = true;
      const page = await services!.activity.messages(id, rows[0].id);
      setHistory(mergeMessages(rows, page || []));
      if (!page?.length) prepend.current = false;
      setMore((page?.length || 0) === 50);
    } catch (e) {
      prepend.current = false;
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  const newest = rows[rows.length - 1]?.id;
  useEffect(() => {
    if (
      !nearBottom ||
      !newest ||
      state.error ||
      !notificationStats.enabled ||
      lastSeen.current === newest
    )
      return;
    lastSeen.current = newest;
    services!.notifications
      .readConversation(id, auth.session!.user.id, newest)
      .then(() => refreshNotifications())
      .catch(() => {
        lastSeen.current = '';
      });
  }, [
    nearBottom,
    newest,
    id,
    state.error,
    notificationStats.enabled,
    refreshNotifications,
    auth.session,
  ]);
  const peer =
    conversation?.client_id === auth.session?.user.id
      ? conversation?.talent_name
      : conversation?.client_name;
  return (
    <Screen
      title={peer || 'Conversation'}
      scroll={false}
      footer={
        <View style={{ gap: 8 }}>
          {conversation && !conversation.can_send && (
            <Label>Messaging is paused because there is no active accepted booking.</Label>
          )}
          {!!error && (
            <Label accessibilityRole="alert" style={{ color: c.error }}>
              {error}
            </Label>
          )}
          <View style={{ flexDirection: 'row', gap: 8, alignItems: 'flex-end' }}>
            <TextInput
              accessibilityLabel="Message"
              placeholder="Write a message…"
              placeholderTextColor={c.secondary}
              multiline
              value={text}
              onChangeText={setText}
              maxLength={4000}
              editable={!!conversation?.can_send && !busy}
              style={{
                flex: 1,
                minHeight: 48,
                maxHeight: 120,
                padding: 12,
                borderRadius: 16,
                borderWidth: 1,
                borderColor: c.border,
                fontFamily: f.regular,
                color: c.text,
              }}
            />
            <PrimaryButton
              title={busy ? 'Please wait…' : 'Send message'}
              disabled={busy || !text.trim() || !conversation?.can_send}
              onPress={() => {
                void send();
              }}
            />
          </View>
        </View>
      }
    >
      {newMessages && (
        <PrimaryButton
          title="Jump to latest messages"
          secondary
          onPress={() => {
            scroll.current?.scrollToEnd({ animated: true });
            setNearBottom(true);
            setNewMessages(false);
          }}
        />
      )}
      <ScrollView
        ref={scroll}
        onScroll={(event) => {
          const native = event.nativeEvent;
          offset.current = native.contentOffset.y;
          const bottom = isNearBottom(
            offset.current,
            native.layoutMeasurement.height,
            native.contentSize.height,
          );
          setNearBottom(bottom);
          if (bottom) setNewMessages(false);
        }}
        scrollEventThrottle={100}
        onContentSizeChange={(_width, next) => {
          const target = chatScrollTarget(
            height.current,
            next,
            offset.current,
            prepend.current,
            nearBottom,
            forceBottom.current,
          );
          if (target.kind === 'offset') scroll.current?.scrollTo({ y: target.y, animated: false });
          else if (target.kind === 'end') {
            scroll.current?.scrollToEnd({ animated: !forceBottom.current });
            setNearBottom(true);
            setNewMessages(false);
          } else if (next > height.current) setNewMessages(true);
          height.current = next;
          prepend.current = false;
          forceBottom.current = false;
        }}
        style={{ flex: 1, minHeight: 0 }}
        contentContainerStyle={{ padding: 20, gap: 14 }}
      >
        <Label>{live.status}</Label>
        {window.reset && (
          <Label>Showing latest messages. Load older messages to continue history.</Label>
        )}
        <PrimaryButton title="Refresh messages" secondary onPress={state.refresh} />
        <Feedback {...state} retry={state.refresh} />
        {(more || window.reset) && (state.data?.[1]?.length || 0) >= 50 && (
          <PrimaryButton
            title="Load older messages"
            secondary
            disabled={busy}
            onPress={() => {
              void older();
            }}
          />
        )}
        {rows.map((row) => {
          const mine = row.sender_id === auth.session?.user.id;
          return (
            <View
              key={row.id}
              style={{
                alignSelf: mine ? 'flex-end' : 'flex-start',
                maxWidth: '88%',
                padding: 14,
                borderRadius: 18,
                backgroundColor: mine ? c.primaryDark : c.white,
                borderWidth: 1,
                borderColor: c.border,
              }}
            >
              <Text
                style={{
                  fontFamily: f.regular,
                  fontSize: 13,
                  lineHeight: 21,
                  color: mine ? c.white : c.text,
                }}
              >
                {row.body}
              </Text>
              <Text
                style={{
                  fontFamily: f.medium,
                  fontSize: 11,
                  color: mine ? c.white : c.secondary,
                  marginTop: 7,
                }}
              >
                {new Date(row.created_at).toLocaleString()}
              </Text>
            </View>
          );
        })}
        {!state.loading && !state.error && !rows.length && (
          <EmptyState
            title="Start your conversation"
            detail="Messages are shared with the other booking participant."
          />
        )}
      </ScrollView>
    </Screen>
  );
}
