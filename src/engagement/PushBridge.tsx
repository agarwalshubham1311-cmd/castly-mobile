import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { router, usePathname } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { beforeLogout } from './logout';
import { pushBuildEnabled, registerPush, unregisterPush } from './push';
import { deliveryId, deliveryPathId, safeNotificationRoute } from './types';
export default function PushBridge() {
  const auth = useAuth(), actor = auth.session?.user.id;
  const [pending, setPending] = useState<string | null>(null);
  const path = usePathname();
  useEffect(() => { const id = deliveryPathId(path); if (!actor && id) void Promise.resolve().then(() => setPending(id)); }, [actor, path]);
  const handled = useRef(new Set<string>());
  useEffect(() => beforeLogout(unregisterPush), []);
  useEffect(() => {
    if (!pushBuildEnabled()) return;
    let active = true;
    const subscriptions: { remove(): void }[] = [];
    void import('expo-notifications').then(async (n) => {
      if (!active) return;
      n.setNotificationHandler({ handleNotification: async () => ({ shouldShowBanner: false, shouldShowList: false, shouldPlaySound: false, shouldSetBadge: false }) });
      subscriptions.push(n.addNotificationResponseReceivedListener((response) => { const id = deliveryId(response.notification.request.content.data); if (id && active) setPending(id); }));
      const last = await n.getLastNotificationResponseAsync();
      if (active && last) setPending(deliveryId(last.notification.request.content.data));
      subscriptions.push(n.addPushTokenListener(() => { if (actor) void registerPush(actor, false).catch(() => {}); }));
    }).catch(() => {});
    const resume = AppState.addEventListener('change', (state) => { if (state === 'active' && actor) void registerPush(actor, false).catch(() => {}); });
    if (actor) void registerPush(actor, false).catch(() => {});
    return () => { active = false; subscriptions.forEach((s) => s.remove()); resume.remove(); };
  }, [actor]);
  useEffect(() => {
    if (!actor || !pending || !services || handled.current.has(actor + ':' + pending)) return;
    let current = true;
    const id = pending;
    if (deliveryPathId(path) === id) { handled.current.add(actor + ':' + id); void Promise.resolve().then(() => { if(current)setPending(null); }); return () => { current=false; }; }
    void services.engagement.open(actor, id).then((result) => {
      if (!current) return;
      if (handled.current.size > 100) handled.current.clear();
      handled.current.add(actor + ':' + id); setPending(null);
      if (safeNotificationRoute(result.route)) router.push(result.route as '/notifications');
    }).catch(() => { if (current) setPending(null); });
    return () => { current = false; };
  }, [actor, pending, path]);
  return null;
}
