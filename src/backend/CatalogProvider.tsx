import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { talents as fixtures, type Talent, type Category } from '../talent';
import { configuration, services } from './index';
import { readableError } from './errors';
import { useAuth } from '../auth/AuthProvider';
const names: Record<string, Category> = {
  actors: 'Actors',
  models: 'Models',
  creators: 'Creators',
  musicians: 'Musicians',
  photographers: 'Photographers',
};
function useCatalogState() {
  const auth = useAuth();
  const mock = configuration.mode === 'mock';
  const [talents, setTalents] = useState<Talent[]>(mock ? fixtures : []);
  const [loading, setLoading] = useState(!mock);
  const [error, setError] = useState('');
  const request = useRef(0);
  const refresh = useCallback(async () => {
    if (!services) return;
    const token = ++request.current;
    try {
      const [profiles, assets] = await Promise.all([
        services.talent.published(),
        services.portfolio.published(),
      ]);
      const rows = await Promise.all(
        (profiles || []).map(async (profile): Promise<Talent> => {
          const portraitUri = profile.portrait_path
            ? await services!.media.signedUrl(profile.portrait_path)
            : undefined;
          const portfolio = await Promise.all(
            (assets || [])
              .filter((asset) => asset.talent_id === profile.id)
              .map(async (asset) => ({
                image: asset.id,
                label: asset.label,
                uri: await services!.media.signedUrl(asset.path),
              })),
          );
          return {
            id: profile.id,
            name: profile.display_name,
            city: profile.city,
            category: names[profile.category_id] || 'Creators',
            rate: profile.day_rate,
            bio: profile.bio,
            skills: profile.skills,
            available: profile.available,
            portrait: '',
            portraitUri,
            portfolio,
            remote: true,
          };
        }),
      );
      if (token === request.current) {
        setTalents(rows);
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
    return () => {
      clearInterval(timer);
      // eslint-disable-next-line react-hooks/exhaustive-deps -- request is a generation counter, not a DOM ref.
      request.current++;
    };
  }, [mock, refresh, auth.session?.user.id]);
  return { talents, loading, error, refresh, mock };
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
