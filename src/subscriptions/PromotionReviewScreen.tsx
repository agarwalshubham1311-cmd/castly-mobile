import { useSessionGuard } from './useSessionGuard';
import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Screen, Field, PrimaryButton } from '../components/ui';
import type { PromotionClaim } from './catalog';
function ReviewContent() {
  const { session } = useAuth();
  const begin = useSessionGuard(session?.user.id);
  const [claims, setClaims] = useState<(PromotionClaim & { user_id: string })[]>([]),
    [reason, setReason] = useState(''),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const load = async () => {
    const valid = begin();
    try {
      if (!services) throw new Error('Sign in with an authorized reviewer account.');
      const rows = await services.subscriptions.reviewQueue();
      if (valid()) setClaims(rows);
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Review queue unavailable.');
    }
  };
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const review = async (id: string, decision: 'approve' | 'reject') => {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      if (!session || !services) throw new Error('Sign in again.');
      await services.subscriptions.review(id, decision, reason.trim(), session.user.id);
      if (valid()) {
        setReason('');
        await load();
      }
    } catch (e) {
      if (valid())
        setError(e instanceof Error ? e.message : 'Review failed. Refresh before retrying.');
    } finally {
      if (valid()) setBusy(false);
    }
  };
  return (
    <Screen title="Founding offer review">
      <Text>
        Confirm the public video promotes Castly and belongs to this creator. Record your evidence
        and reason. Decisions are final.
      </Text>
      {error ? <Text accessibilityRole="alert">{error}</Text> : null}
      <Field label="Review reason and evidence" value={reason} onChangeText={setReason} />
      {claims.map((claim) => (
        <View key={claim.id}>
          <Text>Creator: {claim.user_id}</Text>
          <Text selectable>{claim.post_url}</Text>
          <PrimaryButton
            title="Approve 12 months"
            disabled={busy || reason.trim().length < 3}
            onPress={() => void review(claim.id, 'approve')}
          />
          <PrimaryButton
            title="Reject claim"
            disabled={busy || reason.trim().length < 3}
            onPress={() => void review(claim.id, 'reject')}
          />
        </View>
      ))}
      {!claims.length && !error ? <Text>No pending claims.</Text> : null}
      <PrimaryButton title="Refresh queue" disabled={busy} onPress={() => void load()} />
    </Screen>
  );
}

export default function PromotionReviewScreen() {
  const { session } = useAuth();
  return <ReviewContent key={session?.user.id || 'guest'} />;
}
