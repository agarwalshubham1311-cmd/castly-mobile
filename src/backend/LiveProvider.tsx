import React, { createContext, useContext, useEffect, useMemo, useState, useCallback } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { supabase } from './client';
import { businessMode } from './index';
import { LiveHub, type LivePort, type LiveTopic } from './liveHub';
const Context = createContext<{ hub: LiveHub | null; status: string }>({
  hub: null,
  status: 'Live updates unavailable. Refresh to check for changes.',
});
export function LiveProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const [status, setStatus] = useState('Connecting live updates…');
  const subject = auth.session?.user.id,
    token = auth.session?.access_token,
    role = auth.account?.primary_role;
  const hub = useMemo(
    () =>
      supabase && subject && token && businessMode === 'spring'
        ? new LiveHub(supabase as unknown as LivePort, subject, role || null, token, setStatus)
        : null,
    [subject, token, role],
  );
  useEffect(() => {
    if (!hub) return;
    void hub.start();
    let lastPoll = Date.now();
    const timer = setInterval(() => {
      const due = hub.getStatus() === 'Live updates connected' ? 60000 : 15000;
      if (
        (AppState.currentState === 'active' || AppState.currentState == null) &&
        Date.now() - lastPoll >= due
      ) {
        lastPoll = Date.now();
        hub.poll();
      }
    }, 15000);
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active') hub.poll();
    });
    return () => {
      clearInterval(timer);
      listener.remove();
      hub.dispose();
    };
  }, [hub]);
  return <Context.Provider value={{ hub, status }}>{children}</Context.Provider>;
}
export function useLiveContext() {
  return useContext(Context);
}
export function useFocusedLive(topic: LiveTopic, conversation?: string) {
  const { hub, status } = useLiveContext();
  const [revision, setRevision] = useState(0);
  useFocusEffect(
    useCallback(
      () => hub?.watch(topic, conversation, () => setRevision((v) => v + 1)),
      [hub, topic, conversation],
    ),
  );
  return { revision, status, refresh: () => setRevision((v) => v + 1) };
}
