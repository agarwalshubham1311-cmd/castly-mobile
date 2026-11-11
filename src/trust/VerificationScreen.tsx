import React, { useEffect, useState } from 'react';
import { Text, View, Pressable } from 'react-native';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Screen, Field, PrimaryButton, Label } from '../components/ui';
import { c, f } from '../theme';
import { useTrustGuard } from './useTrustGuard';
import type { Verification } from './api';
export default function VerificationScreen() {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [request, setRequest] = useState<Verification | null>(null),
    [statement, setStatement] = useState(''),
    [assets, setAssets] = useState<{ id: string; label: string }[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [consent, setConsent] = useState(false),
    [history, setHistory] = useState<{ id: string; decision: string; reason: string }[]>([]),
    [contact, setContact] = useState<{
      email_verified: boolean | null;
      phone_verified: boolean | null;
    } | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const load = async () => {
    const valid = begin();
    try {
      if (!services || !session) throw new Error('Sign in to view verification.');
      const [r, a, h, v] = await Promise.all([
        services.trust.verification(),
        services.portfolio.list(session.user.id),
        services.trust.verificationHistory(),
        services.trust.contact(),
      ]);
      if (valid()) {
        setRequest(r);
        setAssets(a);
        setHistory(h);
        setContact(v);
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Verification unavailable.');
    }
  };
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const submit = async () => {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      if (!services || !session) return;
      await services.trust.applyVerification(statement, selected, session.user.id);
      if (valid()) await load();
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Submission failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  };
  return (
    <Screen title="Creator verification">
      <Text style={{ fontFamily: f.bold, fontSize: 22, color: c.text }}>
        Profile and portfolio review
      </Text>
      <Label>
        Verified Creator means Castly reviewed your profile and submitted portfolio. It is not an
        identity or safety guarantee. Do not submit government IDs or biometric information.
      </Label>
      {contact ? (
        <Label>
          Email:{' '}
          {contact.email_verified === null
            ? 'not confirmed with provider'
            : contact.email_verified
              ? 'confirmed by provider'
              : 'not confirmed'}{' '}
          · Phone:{' '}
          {contact.phone_verified === null
            ? 'unknown'
            : contact.phone_verified
              ? 'confirmed by provider'
              : 'not confirmed'}
        </Label>
      ) : null}
      <Label>Status: {request?.status || 'Loading'}</Label>
      {request?.expires_at ? (
        <Label>Review expires {new Date(request.expires_at).toLocaleDateString()}</Label>
      ) : null}
      {error ? (
        <Label accessibilityRole="alert" style={{ color: c.error }}>
          {error}
        </Label>
      ) : null}
      {request && ['NOT_SUBMITTED', 'REJECTED', 'EXPIRED', 'REVOKED'].includes(request.status) ? (
        <>
          <Field
            label="Explain your portfolio ownership"
            value={statement}
            onChangeText={setStatement}
            multiline
          />
          <Label>Select up to 10 existing portfolio items for manual review.</Label>
          {assets.map((asset) => (
            <PrimaryButton
              key={asset.id}
              secondary
              title={`${selected.includes(asset.id) ? 'Selected: ' : ''}${asset.label}`}
              onPress={() =>
                setSelected((old) =>
                  old.includes(asset.id)
                    ? old.filter((id) => id !== asset.id)
                    : old.length < 10
                      ? [...old, asset.id]
                      : old,
                )
              }
            />
          ))}
          {!assets.length ? <Label>Add your own portfolio items before applying.</Label> : null}
          <Pressable
            accessibilityRole="checkbox"
            accessibilityState={{ checked: consent }}
            onPress={() => setConsent(!consent)}
            style={{ minHeight: 48, justifyContent: 'center' }}
          >
            <Label>
              {consent ? '✓ ' : ''}I own these works and consent to authorized profile review
              (profile-review-v1).
            </Label>
          </Pressable>
          <PrimaryButton
            title={busy ? 'Submitting…' : 'Submit verification'}
            disabled={busy || !consent || statement.trim().length < 20 || !selected.length}
            onPress={() => void submit()}
          />
        </>
      ) : null}
      {history.map((item) => (
        <View key={item.id}>
          <Label>{item.decision}</Label>
          <Label>{item.reason}</Label>
        </View>
      ))}
      <PrimaryButton title="Refresh verification" disabled={busy} onPress={() => void load()} />
    </Screen>
  );
}
