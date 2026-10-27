import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { supabase } from './client';
import { useAuth } from '../auth/AuthProvider';
/** Events invalidate API reads; payloads are never trusted as authorized business data. */
export function useLiveRefresh(
  table: 'bookings' | 'conversations' | 'messages',
  conversationId?: string,
) {
  const auth = useAuth();
  const [revision, setRevision] = useState(0);
  const [status, setStatus] = useState('Connecting live updates…');
  const subject = auth.session?.user.id;
  const token = auth.session?.access_token;
  const role = auth.account?.primary_role;
  const serial = useRef(0);
  useEffect(() => {
    if (!supabase || !subject || !token) return;
    let active = true;
    const channel = supabase.channel(
      `castly-${table}-${subject}-${conversationId || 'list'}-${++serial.current}`,
    );
    const refresh = () => {
      if (active) setRevision((value) => value + 1);
    };
    const filter =
      table === 'messages'
        ? `conversation_id=eq.${conversationId}`
        : `${role === 'talent' ? 'talent_id' : 'client_id'}=eq.${subject}`;
    supabase.realtime
      .setAuth(token)
      .then(() => {
        if (!active) return;
        channel.on(
          'postgres_changes',
          { event: 'INSERT', schema: 'public', table, filter },
          refresh,
        );
        if (table === 'bookings')
          channel.on(
            'postgres_changes',
            { event: 'UPDATE', schema: 'public', table, filter },
            refresh,
          );
        channel.subscribe((state) => {
          if (!active) return;
          setStatus(
            state === 'SUBSCRIBED'
              ? 'Live updates connected'
              : 'Live updates unavailable. Refresh to check for changes.',
          );
          if (state === 'SUBSCRIBED') refresh();
        });
      })
      .catch(() => {
        if (active) setStatus('Live updates unavailable. Refresh to check for changes.');
      });
    const timer = setInterval(() => {
      if (AppState.currentState === 'active' || AppState.currentState == null) refresh();
    }, 15000);
    const app = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => {
      active = false;
      clearInterval(timer);
      app.remove();
      void supabase!.removeChannel(channel);
    };
  }, [subject, token, table, conversationId, role]);
  return { revision, status, refresh: () => setRevision((value) => value + 1) };
}
