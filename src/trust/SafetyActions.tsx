import { safetyConfiguration } from './configuration';
import React, { useEffect, useState } from 'react';
import { router } from 'expo-router';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Label, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
export function SafetyActions({
  kind,
  id,
  allowBlock = false,
}: {
  kind: 'user' | 'portfolio' | 'message' | 'review';
  id: string;
  allowBlock?: boolean;
}) {
  const { session } = useAuth();
  const begin = useTrustGuard(`${session?.user.id}:${id}`);
  const [enabled, setEnabled] = useState(false),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  useEffect(() => {
    const valid = begin();
    void safetyConfiguration(session?.user.id || '')
      .then((c) => {
        if (valid()) setEnabled(c.features.reporting);
      })
      .catch(() => {});
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  async function block() {
    const valid = begin();
    setBusy(true);
    try {
      await services!.trust.block(id, true, session!.user.id);
      if (valid())
        setMessage(
          'Account blocked. New bookings and messages are prevented. Manage blocks in Safety Center.',
        );
    } catch (e) {
      if (valid()) setMessage(e instanceof Error ? e.message : 'Blocking failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  if (!enabled || !session || id === session.user.id) return null;
  return (
    <>
      <PrimaryButton
        secondary
        title={`Report ${kind}`}
        onPress={() => router.push({ pathname: '/safety/report', params: { kind, id } })}
      />
      {allowBlock ? (
        <PrimaryButton
          secondary
          title="Block this account"
          disabled={busy}
          onPress={() => void block()}
        />
      ) : null}
      {message ? <Label accessibilityRole="alert">{message}</Label> : null}
    </>
  );
}
