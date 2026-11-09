import { useSessionGuard } from './useSessionGuard';
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Linking, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { PrimaryButton } from '../components/ui';
import { BillingSession, billingFailure, type BillingOffer, type BillingStatus } from './billing';
import { loadNativeBilling } from './billingNative';
export function PlayBilling({ onVerified }: { onVerified: () => void }) {
  const { session } = useAuth();
  const actor = session?.user.id;
  const begin = useSessionGuard(actor);
  const current = useRef(actor);
  const refreshed = useRef(onVerified);
  useLayoutEffect(() => {
    current.current = actor;
    refreshed.current = onVerified;
  }, [actor, onVerified]);
  const billing = useRef<BillingSession | null>(null);
  const [offers, setOffers] = useState<BillingOffer[]>([]),
    [status, setStatus] = useState<BillingStatus>('unavailable'),
    [message, setMessage] = useState(
      'Purchases require a supported Android build and configured Google Play products.',
    ),
    [busy, setBusy] = useState(false),
    [connected, setConnected] = useState(false);
  useEffect(() => {
    let active = true;
    let owned: BillingSession | null = null;
    void (async () => {
      if (!services || !actor) return;
      try {
        const config = await services.subscriptions.billingConfiguration();
        if (!active || !config.enabled) return;
        const native = await loadNativeBilling();
        if (!active || !native) return;
        owned = new BillingSession(
          native,
          config,
          actor,
          () => current.current,
          (token, restore) =>
            restore
              ? services!.subscriptions.restore(token, actor)
              : services!.subscriptions.verify(token, actor),
          (s, m) => {
            if (active) {
              setStatus(s);
              if (m) setMessage(m);
              if (['confirmed', 'expired', 'inactive'].includes(s)) refreshed.current();
            }
          },
        );
        billing.current = owned;
        await owned.connect();
        if (active) {
          setOffers(owned.offers);
          setConnected(true);
        }
      } catch {
        if (active) {
          setStatus('error');
          setMessage('Billing is unavailable. Refresh membership or try again.');
        }
      }
    })();
    return () => {
      active = false;
      billing.current = null;
      if (owned) void owned.close().catch(() => {});
    };
  }, [actor]);
  const run = async (action: () => Promise<unknown>) => {
    const valid = begin();
    setBusy(true);
    try {
      await action();
    } catch (error) {
      if (!valid()) return;
      const failure = billingFailure(error);
      setStatus(failure.status);
      setMessage(failure.message);
    } finally {
      if (valid()) setBusy(false);
    }
  };
  return (
    <View style={{ gap: 12 }}>
      <Text accessibilityRole="header">Google Play subscription</Text>
      <Text accessibilityRole={status === 'error' ? 'alert' : undefined}>{message}</Text>
      {offers.map((offer) => (
        <View key={offer.token}>
          <Text>{offer.phases.join(' · ') || offer.label}</Text>
          <PrimaryButton
            title={`Subscribe · ${offer.label}`}
            disabled={busy || ['pending', 'verifying', 'connecting'].includes(status)}
            onPress={() => void run(() => billing.current!.purchase(offer))}
          />
        </View>
      ))}
      {!offers.length ? (
        <PrimaryButton title="Purchases unavailable" disabled onPress={() => {}} />
      ) : null}
      <PrimaryButton
        title={busy ? 'Please wait…' : 'Restore purchases'}
        disabled={busy || !connected}
        onPress={() => void run(() => billing.current!.restore())}
      />
      <PrimaryButton
        title="Manage in Google Play"
        onPress={() => void Linking.openURL('https://play.google.com/store/account/subscriptions')}
      />
    </View>
  );
}
