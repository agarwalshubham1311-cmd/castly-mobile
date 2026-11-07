import React, { createContext, useContext, useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { businessMode, services } from './index';
import { useLiveContext } from './LiveProvider';
import { readableError } from './errors';
import type { NotificationStats } from './types';
function useNotificationsState() {
  const auth = useAuth();
  const { hub } = useLiveContext();
  const [stats, setStats] = useState<NotificationStats>({ enabled: false });
  const [error, setError] = useState('');
  const generation = useRef(0);
  const pending = useRef<Promise<void> | null>(null);
  const dirty = useRef(false);
  const owner = auth.session?.user.id;
  const refresh = useCallback(() => {
    if (!owner || businessMode !== 'spring' || !services) return Promise.resolve();
    if (pending.current) {
      dirty.current = true;
      return pending.current;
    }
    const token = ++generation.current;
    const work = services.notifications
      .stats()
      .then((row) => {
        if (token === generation.current) {
          setStats(row);
          setError('');
          hub?.enableNotifications(row.enabled);
        }
      })
      .catch((e) => {
        if (token === generation.current) {
          setError(readableError(e));
          setStats({ enabled: false });
          hub?.enableNotifications(false);
        }
      });
    pending.current = work;
    void work.finally(() => {
      if (pending.current === work) {
        pending.current = null;
        if (dirty.current) {
          dirty.current = false;
          hub?.invalidate('notifications');
        }
      }
    });
    return work;
  }, [owner, hub]);
  useEffect(() => {
    if (!owner) return;
    void refresh();
    const off = hub?.watch('notifications', undefined, () => {
      void refresh();
    });
    return () => {
      off?.(); // eslint-disable-next-line react-hooks/exhaustive-deps -- generation is an async-request counter, not a DOM ref.
      generation.current++;
      pending.current = null;
      dirty.current = false;
    };
  }, [owner, hub, refresh]);
  return { stats, error, refresh };
}
type State = ReturnType<typeof useNotificationsState>;
const Context = createContext<State>({
  stats: { enabled: false },
  error: '',
  refresh: async () => {},
});
export function NotificationProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  return <Scoped key={auth.session?.user.id || 'guest'}>{children}</Scoped>;
}
function Scoped({ children }: { children: React.ReactNode }) {
  const state = useNotificationsState();
  return <Context.Provider value={state}>{children}</Context.Provider>;
}
export function useNotifications() {
  return useContext(Context);
}
