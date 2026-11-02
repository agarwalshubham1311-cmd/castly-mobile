import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { talents as fixtures, categories as fixtureCategories, type Talent } from '../talent';
import { configuration, services } from './index';
import { collectPages, mapTalent, supportedCategories } from './catalog';
import { readableError } from './errors';
import { useAuth } from '../auth/AuthProvider';
function useCatalogState() {
  const auth = useAuth();
  const mock = configuration.mode === 'mock';
  const [talents, setTalents] = useState<Talent[]>(mock ? fixtures : []);
  const [categories, setCategories] = useState([...fixtureCategories]);
  const [loading, setLoading] = useState(!mock);
  const [error, setError] = useState('');
  const [mediaWarning, setMediaWarning] = useState('');
  const request = useRef(0);
  const refresh = useCallback(async () => {
    if (!services) return;
    const token = ++request.current;
    setLoading(true);
    try {
      const [profiles, assets, categoryRows] = await Promise.all([
        collectPages((offset) => services!.talent.published({ limit: 200, offset })),
        collectPages((offset) => services!.portfolio.published(offset)),
        services.talent.categories(),
      ]);
      const rows = await Promise.all(
        profiles.map((profile) => mapTalent(profile, assets, services!.media.signedUrl)),
      );
      if (token === request.current) {
        setTalents(rows.map((row) => row.talent));
        setCategories(supportedCategories(categoryRows || []));
        setMediaWarning(
          rows.some((row) => row.mediaUnavailable)
            ? 'Some photographs are unavailable. Retry discovery to reload them.'
            : '',
        );
        setError('');
      }
    } catch (e) {
      if (token === request.current) {
        setTalents([]);
        setError(readableError(e));
      }
    } finally {
      if (token === request.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    if (mock) return;
    void Promise.resolve().then(refresh);
    const timer = setInterval(() => {
      if (AppState.currentState === 'active' || AppState.currentState == null) void refresh();
    }, 45000);
    const foreground = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refresh();
    });
    return () => {
      clearInterval(timer);
      foreground.remove();
      // eslint-disable-next-line react-hooks/exhaustive-deps -- request is an invalidation generation.
      request.current++;
    };
  }, [mock, refresh, auth.session?.user.id]);
  return { talents, categories, loading, error, mediaWarning, refresh, mock };
}
type Catalog = ReturnType<typeof useCatalogState>;
const Context = createContext<Catalog | null>(null);
export function CatalogProvider({ children }: { children: React.ReactNode }) {
  const data = useCatalogState();
  return <Context.Provider value={data}>{children}</Context.Provider>;
}
export function useCatalog() {
  const data = useContext(Context);
  if (!data) throw new Error('CatalogProvider is required');
  return data;
}
