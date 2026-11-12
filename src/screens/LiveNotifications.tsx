import React, { useEffect, useState } from 'react';
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
  loadNotificationWindow,
} from '../backend/notifications';
import type { NotificationRow } from '../backend/types';
import { Screen, Label, Heading, PrimaryButton, EmptyState } from '../components/ui';
import { c } from '../theme';
export default function LiveNotifications() {
  const auth = useAuth();
  const notices = useNotifications();
  const live = useLiveRefresh('notifications');
  const owner = auth.session?.user.id;
  const [snapshot, setSnapshot] = useState<{
    owner?: string;
    rows: NotificationRow[];
    more: boolean;
  }>();
  const [scope, setScope] = useState({ owner, pages: 1 });
  const pages = scope.owner === owner ? scope.pages : 1;
  const rows = snapshot && snapshot.owner === owner ? snapshot.rows : [];
  const more = snapshot && snapshot.owner === owner && snapshot.more;
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    loadNotificationWindow(services!.notifications.list, pages)
      .then((page) => {
        if (active) {
          setSnapshot({ owner, ...page });
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
  }, [live.revision, revision, pages, owner]);
  const read = async (row: NotificationRow, navigate: boolean) => {
    setBusy(true);
    setError('');
    try {
      if (!row.read_at) {
        await services!.notifications.read(row.id, owner);
        setSnapshot((current) =>
          current && current.owner === owner
            ? {
                ...current,
                rows: current.rows.map((item) =>
                  item.id === row.id ? { ...item, read_at: new Date().toISOString() } : item,
                ),
              }
            : current,
        );
        await notices.refresh();
      }
      if (navigate) {
        const route = notificationRoute(row);
        if (route) router.push(route);
      }
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title="Notifications">
      <Label>{live.status}</Label>
      <EngagementEntry />
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
            {!row.read_at && (
              <PrimaryButton
                title="Mark as read"
                secondary
                disabled={busy}
                onPress={() => {
                  void read(row, false);
                }}
              />
            )}
            <PrimaryButton
              title={row.kind === 'message_received' ? 'View conversation' : 'View booking'}
              secondary
              disabled={busy}
              onPress={() => {
                void read(row, true);
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
          disabled={busy || loading}
          onPress={() => {
            setLoading(true);
            setScope({ owner, pages: pages + 1 });
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

function EngagementEntry() {
  const [enabled,setEnabled]=useState(false);
  useEffect(()=>{let active=true;void services?.engagement.configuration().then((c)=>{if(active)setEnabled(c.push||c.email||c.engagement||c.analytics);}).catch(()=>{});return()=>{active=false;};},[]);
  return enabled?<PrimaryButton secondary title="Notification & engagement preferences" onPress={()=>router.push('/engagement')}/>:null;
}
