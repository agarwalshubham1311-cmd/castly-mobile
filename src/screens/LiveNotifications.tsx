import React, { useCallback, useEffect, useState } from 'react';
import { View } from 'react-native';
import { router } from 'expo-router';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { useNotifications } from '../backend/NotificationProvider';
import { useLiveRefresh } from '../backend/useLiveRefresh';
import { readableError } from '../backend/errors';
import {
  notificationTitles,
  notificationRoute,
  mergeNotifications,
} from '../backend/notifications';
import type { NotificationRow } from '../backend/types';
import { Screen, Label, Heading, PrimaryButton, EmptyState } from '../components/ui';
import { c } from '../theme';
export default function LiveNotifications() {
  const auth = useAuth();
  const notices = useNotifications();
  const live = useLiveRefresh('notifications');
  const [rows, setRows] = useState<NotificationRow[]>([]),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [more, setMore] = useState(false);
  const [revision, setRevision] = useState(0);
  const load = useCallback(async () => {
    try {
      const page = await services!.notifications.list();
      setRows(page);
      setMore(page.length === 50);
      setError('');
    } catch (e) {
      setError(readableError(e));
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    let active = true;
    services!.notifications
      .list()
      .then((page) => {
        if (active) {
          setRows(page);
          setMore(page.length === 50);
          setError('');
        }
      })
      .catch((e) => {
        if (active) setError(readableError(e));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [live.revision, revision]);
  const older = async () => {
    if (!rows.length) return;
    setBusy(true);
    try {
      const page = await services!.notifications.list(rows[rows.length - 1].id);
      setRows((current) => mergeNotifications(current, page));
      setMore(page.length === 50);
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  const open = async (row: NotificationRow) => {
    setBusy(true);
    setError('');
    try {
      await services!.notifications.read(row.id, auth.session!.user.id);
      await notices.refresh();
      const route = notificationRoute(row);
      if (route) router.push(route);
      else await load();
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title="Notifications">
      <Label>{live.status}</Label>
      <PrimaryButton
        title="Refresh notifications"
        secondary
        onPress={() => {
          setLoading(true);
          setRevision((v) => v + 1);
          void notices.refresh();
        }}
      />
      {loading && <Label>Loading notifications…</Label>}
      {!!error && (
        <EmptyState
          title="Notifications unavailable"
          detail={error}
          action="Retry notifications"
          onPress={() => {
            setLoading(true);
            setRevision((v) => v + 1);
          }}
        />
      )}
      {!error &&
        rows.map((row) => (
          <View
            key={row.id}
            style={{
              padding: 16,
              borderRadius: 18,
              gap: 8,
              borderWidth: 1,
              borderColor: c.border,
              backgroundColor: row.read_at ? c.white : c.lavender,
            }}
          >
            <Heading>{notificationTitles[row.kind]}</Heading>
            <Label>
              {row.read_at ? 'Read notification' : 'Unread notification'} ·{' '}
              {new Date(row.created_at).toLocaleString()}
            </Label>
            <PrimaryButton
              title={row.kind === 'message_received' ? 'View conversation' : 'View booking'}
              secondary
              disabled={busy}
              onPress={() => {
                void open(row);
              }}
            />
          </View>
        ))}
      {!loading && !error && !rows.length && (
        <EmptyState
          title="No notifications yet"
          detail="New booking requests, decisions and messages will appear here."
        />
      )}
      {more && !error && (
        <PrimaryButton
          title="Load older notifications"
          secondary
          disabled={busy}
          onPress={() => {
            void older();
          }}
        />
      )}
      <Label>
        Notification read state is private to you. Message read receipts and push notifications are
        not enabled.
      </Label>
    </Screen>
  );
}
