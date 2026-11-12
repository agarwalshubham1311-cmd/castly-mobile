import React, { useEffect, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuth } from '../../../src/auth/AuthProvider';
import { services } from '../../../src/backend';
import { Screen, Label, PrimaryButton } from '../../../src/components/ui';
import { safeNotificationRoute, uuidPattern } from '../../../src/engagement/types';
export default function OpenNotification() {
  const { id } = useLocalSearchParams<{ id: string }>(), auth = useAuth(), actor = auth.session?.user.id;
  const [error, setError] = useState(''), [revision, setRevision] = useState(0);
  useEffect(() => { if (!actor || !services || !uuidPattern.test(id || '')) { return; } let current = true; void services.engagement.open(actor, id).then((result) => { if (current && safeNotificationRoute(result.route)) router.replace(result.route as '/notifications'); }).catch(() => { if (current) setError('This notification is unavailable for the current account.'); }); return () => { current = false; }; }, [actor, id, revision]);
  return <Screen title="Open Castly update"><Label>{!actor || !uuidPattern.test(id || '') ? 'This notification is unavailable.' : error || 'Authorizing this notification…'}</Label><PrimaryButton title="Retry notification" onPress={() => setRevision((r) => r + 1)} /><PrimaryButton secondary title="Open in-app notifications" onPress={() => router.replace('/notifications')} /></Screen>;
}
