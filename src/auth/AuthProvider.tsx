import * as Linking from 'expo-linking';
import React, { createContext, useContext, useEffect, useState, useRef } from 'react';
import { AppState, Platform } from 'react-native';
import type { Session } from '@supabase/supabase-js';
import { supabase } from '../backend/client';
import { configuration, services } from '../backend';
import { readableError } from '../backend/errors';
import type { Account, Role } from '../backend/types';
function useAuthState() {
  const [session, setSession] = useState<Session | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [loading, setLoading] = useState(configuration.mode === 'supabase');
  const [error, setError] = useState(configuration.mode === 'invalid' ? configuration.error : '');
  const [revision, setRevision] = useState(0);
  const acceptedUser = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (!supabase) return;
    let active = true;
    let events = 0;
    const acceptSession = (next: Session | null) => {
      if (!active) return;
      const id = next?.user.id ?? null;
      if (acceptedUser.current !== id) {
        acceptedUser.current = id;
        setAccount(null);
        setLoading(!!next);
        setError('');
      }
      setSession(next);
    };
    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((_event, next) => {
      events++;
      acceptSession(next);
    });
    const atStart = events;
    supabase.auth
      .getSession()
      .then(({ data, error: restoreError }) => {
        if (!active || events !== atStart) return;
        if (restoreError) {
          setError(readableError(restoreError));
          setLoading(false);
          return;
        }
        acceptSession(data.session);
      })
      .catch((e) => {
        if (active) {
          setError(readableError(e));
          setLoading(false);
        }
      });
    // Web refresh follows browser visibility; native refresh follows AppState.
    if (Platform.OS !== 'web' && AppState.currentState === 'active')
      supabase.auth.startAutoRefresh();
    const appState =
      Platform.OS !== 'web'
        ? AppState.addEventListener('change', (state) => {
            if (state === 'active') supabase!.auth.startAutoRefresh();
            else supabase!.auth.stopAutoRefresh();
          })
        : null;
    return () => {
      active = false;
      subscription.unsubscribe();
      appState?.remove();
      if (Platform.OS !== 'web') supabase!.auth.stopAutoRefresh();
    };
  }, []);
  const userId = session?.user.id;
  useEffect(() => {
    if (!userId || !services) return;
    let active = true;
    services.account
      .get(userId)
      .then((row) => {
        if (active) {
          setAccount(row);
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
  }, [userId, revision]);

  return {
    mode: configuration.mode,
    session,
    account,
    loading,
    error,
    login: async (email: string, password: string) => {
      if (!services) throw new Error('Authentication is unavailable in mock mode.');
      await services.auth.login(email, password);
    },
    signup: async (email: string, password: string, name: string) => {
      if (!services) throw new Error('Authentication is unavailable in mock mode.');
      return services.auth.signup(email, password, name, Linking.createURL('auth/callback'));
    },
    logout: async () => {
      if (!services) return;
      await services.auth.logout();
      setSession(null);
      setAccount(null);
      setError('');
      setLoading(false);
    },
    onboard: async (role: Role, name: string) => {
      if (!services) throw new Error('Authentication required');
      const row = await services.account.onboard(role, name);
      setAccount(row);
      return row;
    },
    refresh: () => {
      if (!session && supabase) {
        setLoading(true);
        supabase.auth
          .getSession()
          .then(({ data, error: restoreError }) => {
            if (restoreError) {
              setError(readableError(restoreError));
              setLoading(false);
            } else {
              acceptedUser.current = data.session?.user.id ?? null;
              setSession(data.session);
              setError('');
              setLoading(!!data.session);
            }
          })
          .catch((e) => {
            setError(readableError(e));
            setLoading(false);
          });
      } else {
        setLoading(true);
        setRevision((value) => value + 1);
      }
    },
  };
}
type Auth = ReturnType<typeof useAuthState>;
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuthState();
  return <Context.Provider value={auth}>{children}</Context.Provider>;
}
export function useAuth() {
  const auth = useContext(Context);
  if (!auth) throw new Error('AuthProvider is required');
  return auth;
}
