import React, { createContext, useContext, useEffect, useState } from 'react';
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
  const [saved, setSaved] = useState<string[]>([]);
  const [hydrated, setHydrated] = useState(false);
  const [storageError, setStorageError] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<Category>(categories[0]);
  const [filters, setFilters] = useState<Filters>({ ...defaultFilters });
  const [sort, setSort] = useState<SortOrder>('Recommended');
  const [bookings, setBookings] = useState<Booking[]>(demoBookings);
  const [conversations, setConversations] = useState<Conversation[]>(demoConversations);
  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(SAVED_KEY)
      .then((value) => {
        if (active)
          setSaved(
            parseSaved(
              value,
              talents.map((t) => t.id),
            ),
          );
      })
      .catch(() => {
        if (active) setStorageError('Saved talent could not be restored on this device.');
      })
      .finally(() => {
        if (active) setHydrated(true);
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (hydrated)
      AsyncStorage.setItem(SAVED_KEY, JSON.stringify(saved)).catch(() =>
        setStorageError(
          'Changes are available this session, but could not be saved to this device.',
        ),
      );
  }, [saved, hydrated]);
  const toggleSaved = (id: string) =>
    setSaved((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : [...current, id],
    );
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
    bookings,
    addBooking,
    conversations,
    openConversation,
    sendMessage,
    archiveConversation,
  };
}
type Store = ReturnType<typeof useDemoStore>;
const Context = createContext<Store | null>(null);
export function DemoProvider({ children }: { children: React.ReactNode }) {
  const store = useDemoStore();
  return <Context.Provider value={store}>{children}</Context.Provider>;
}
export function useDemo() {
  const value = useContext(Context);
  if (!value) throw new Error('DemoProvider is required');
  return value;
}
