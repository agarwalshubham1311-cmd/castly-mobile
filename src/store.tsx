import { router } from 'expo-router';
import { useAuth } from './auth/AuthProvider';
import { services } from './backend';
import { readableError } from './backend/errors';
import React, { createContext, useContext, useEffect, useState, useRef } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { categories, Category, defaultFilters, Filters, SortOrder, talents } from './talent';
import {
  Booking,
  BookingForm,
  Conversation,
  demoBookings,
  demoConversations,
  parseSaved,
} from './demo';
const SAVED_KEY = 'castly.saved.v1';
function useDemoStore() {
  const auth = useAuth();
  const mock = auth.mode === 'mock';
  const userId = auth.session?.user.id;
  const [saveBusy, setSaveBusy] = useState(false);
  const saveLock = useRef(false);

  const [saved, setSaved] = useState<string[]>([]);
  const [hydrated, setHydrated] = useState(false);
  const [storageError, setStorageError] = useState('');
  const [restoreFailed, setRestoreFailed] = useState(false);
  const [savedRevision, setSavedRevision] = useState(0);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<Category>(categories[0]);
  const [filters, setFilters] = useState<Filters>({ ...defaultFilters });
  const [sort, setSort] = useState<SortOrder>('Recommended');
  const [bookings, setBookings] = useState<Booking[]>(demoBookings);
  const [conversations, setConversations] = useState<Conversation[]>(demoConversations);
  useEffect(() => {
    let active = true;
    const restore = mock
      ? AsyncStorage.getItem(SAVED_KEY).then((value) =>
          parseSaved(
            value,
            talents.map((t) => t.id),
          ),
        )
      : userId
        ? services!.saved.list().then((rows) => (rows || []).map((row) => row.talent_id))
        : Promise.resolve([]);
    restore
      .then((value) => {
        if (active) setSaved(value);
      })
      .catch((error) => {
        if (active) {
          setRestoreFailed(true);
          setStorageError(
            mock ? 'Saved talent could not be restored on this device.' : readableError(error),
          );
        }
      })
      .finally(() => {
        if (active) setHydrated(true);
      });
    return () => {
      active = false;
    };
  }, [mock, userId, savedRevision]);
  useEffect(() => {
    if (hydrated && mock)
      AsyncStorage.setItem(SAVED_KEY, JSON.stringify(saved)).catch(() =>
        setStorageError(
          'Changes are available this session, but could not be saved to this device.',
        ),
      );
  }, [saved, hydrated, mock]);
  const toggleSaved = (id: string) => {
    if (mock) {
      setSaved((current) =>
        current.includes(id) ? current.filter((x) => x !== id) : [...current, id],
      );
      return;
    }
    if (!auth.session) {
      router.push('/login');
      return;
    }
    if (restoreFailed || saveLock.current) return;
    saveLock.current = true;
    setSaveBusy(true);
    setStorageError('');
    const owner = auth.session.user.id;
    const removing = saved.includes(id);
    const request = removing ? services!.saved.remove(owner, id) : services!.saved.add(owner, id);
    request
      .then(() => {
        setSaved((current) => (removing ? current.filter((x) => x !== id) : [...current, id]));
      })
      .catch((e) => {
        setStorageError(readableError(e));
      })
      .finally(() => {
        saveLock.current = false;
        setSaveBusy(false);
      });
  };
  const resetSearch = () => {
    setQuery('');
    setCategory('All talent');
    setFilters({ ...defaultFilters });
    setSort('Recommended');
  };
  const addBooking = (talentId: string, form: BookingForm) => {
    const id = `draft-${Date.now()}`;
    setBookings((current) => [
      { ...form, id, talentId, status: 'Pending', local: true },
      ...current,
    ]);
    return id;
  };
  const openConversation = (talentId: string) =>
    setConversations((current) => {
      const existing = current.find((t) => t.talentId === talentId);
      return existing
        ? current.map((t) =>
            t.talentId === talentId ? { ...t, unread: false, archived: false } : t,
          )
        : [
            {
              id: talentId,
              talentId,
              unread: false,
              archived: false,
              booking: false,
              messages: [],
              updatedLabel: 'Local draft',
            },
            ...current,
          ];
    });
  const sendMessage = (id: string, text: string) =>
    setConversations((current) =>
      current.map((t) =>
        t.id === id
          ? {
              ...t,
              updatedLabel: `Local · ${new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false })}`,
              messages: [
                ...t.messages,
                { id: `local-${Date.now()}`, text: text.trim(), mine: true },
              ],
            }
          : t,
      ),
    );
  const archiveConversation = (id: string) =>
    setConversations((current) =>
      current.map((t) => (t.id === id ? { ...t, archived: !t.archived } : t)),
    );
  return {
    saved,
    hydrated,
    saveBusy,
    restoreFailed,
    retrySaved: () => {
      setHydrated(false);
      setStorageError('');
      setRestoreFailed(false);
      setSavedRevision((v) => v + 1);
    },
    storageError,
    toggleSaved,
    query,
    setQuery,
    category,
    setCategory,
    filters,
    setFilters,
    sort,
    setSort,
    resetSearch,
    bookings: mock ? bookings : [],
    addBooking,
    conversations: mock ? conversations : [],
    openConversation,
    sendMessage,
    archiveConversation,
  };
}
type Store = ReturnType<typeof useDemoStore>;
const Context = createContext<Store | null>(null);
export function DemoProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  return (
    <ScopedDemoProvider key={auth.mode === 'mock' ? 'mock' : auth.session?.user.id || 'guest'}>
      {children}
    </ScopedDemoProvider>
  );
}
function ScopedDemoProvider({ children }: { children: React.ReactNode }) {
  const store = useDemoStore();
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useDemo() {
  const value = useContext(Context);
  if (!value) throw new Error('DemoProvider is required');
  return value;
}
