import React, { useEffect, useState } from 'react';
import { Pressable } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
import type { Dispute } from './api';
export default function DisputesScreen() {
  const { booking, admin } = useLocalSearchParams<{ booking?: string; admin?: string }>();
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [rows, setRows] = useState<Dispute[]>([]),
    [statement, setStatement] = useState(''),
    [consent, setConsent] = useState(false),
    [offset, setOffset] = useState(0),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  async function load() {
    const valid = begin();
    try {
      const next = await services!.trust.disputes(admin === '1', offset);
      if (valid()) setRows(next);
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Disputes unavailable.');
    }
  }
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [offset]); // eslint-disable-line react-hooks/exhaustive-deps
  async function file() {
    if (!booking || !session) return;
    const valid = begin();
    setBusy(true);
    try {
      const dispute = await services!.trust.fileDispute(booking, statement, session.user.id);
      if (valid()) router.replace(`/safety/dispute/${dispute.id}`);
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Submission failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  return (
    <Screen title={admin === '1' ? 'Dispute review queue' : 'Booking disputes'}>
      <Label>
        Disputes are private to the submitting participant and authorized independent reviewers.
        Castly does not provide escrow, refunds, payment guarantees or booking payment processing.
      </Label>
      {booking ? (
        <>
          <Label>
            Describe the concern about this booking. Do not include government IDs, credentials,
            intimate images or unrelated private information. The case has a 365-day retention
            review deadline under dispute-text-v1.
          </Label>
          <Field
            label="Describe the booking dispute"
            value={statement}
            onChangeText={setStatement}
            multiline
            maxLength={4000}
          />
          <Pressable
            accessibilityRole="checkbox"
            accessibilityState={{ checked: consent }}
            onPress={() => setConsent(!consent)}
            style={{ minHeight: 48, justifyContent: 'center' }}
          >
            <Label>
              {consent ? '✓ ' : ''}I consent to private case review and the dispute-text-v1 policy.
            </Label>
          </Pressable>
          <PrimaryButton
            title={busy ? 'Submitting…' : 'Submit dispute'}
            disabled={busy || !consent || statement.trim().length < 20}
            onPress={() => void file()}
          />
        </>
      ) : null}
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {rows.map((row) => (
        <PrimaryButton
          key={row.id}
          secondary
          title={`Booking ${row.booking_id.slice(0, 8)}… · ${row.status}`}
          onPress={() => router.push(`/safety/dispute/${row.id}`)}
        />
      ))}
      {!rows.length ? <Label>No cases on this page.</Label> : null}
      <PrimaryButton
        title="Previous cases"
        disabled={offset === 0}
        onPress={() => setOffset(Math.max(0, offset - 25))}
      />
      <PrimaryButton
        title="More cases"
        disabled={rows.length < 25}
        onPress={() => setOffset(offset + 25)}
      />
      <PrimaryButton
        secondary
        title="Refresh disputes"
        disabled={busy}
        onPress={() => void load()}
      />
    </Screen>
  );
}
