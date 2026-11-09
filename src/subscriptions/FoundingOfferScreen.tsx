import { useSessionGuard } from './useSessionGuard';
import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { Screen, Field, PrimaryButton } from '../components/ui';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { c, f } from '../theme';
import { promotionUrl, type PromotionClaim } from './catalog';
function FoundingContent() {
  const { session } = useAuth();
  const begin = useSessionGuard(session?.user.id);
  const [url, setUrl] = useState(''),
    [claims, setClaims] = useState<PromotionClaim[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const refresh = async () => {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      if (!services || !session) throw new Error('Sign in with your creator account.');
      const result = await services.subscriptions.claims();
      if (valid()) setClaims(result);
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Unable to load your claim.');
    } finally {
      if (valid()) setBusy(false);
    }
  };
  useEffect(() => {
    void Promise.resolve().then(refresh);
  }, [session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const submit = async () => {
    const valid = begin();
    setError('');
    setBusy(true);
    try {
      if (!services || !session) throw new Error('Sign in with your creator account.');
      await services.subscriptions.claim(promotionUrl(url), session.user.id);
      const result = await services.subscriptions.claims();
      if (valid()) setClaims(result);
    } catch (e) {
      if (valid())
        setError(e instanceof Error ? e.message : 'Unable to submit. Refresh before retrying.');
    } finally {
      if (valid()) setBusy(false);
    }
  };
  return (
    <Screen title="Founding Creator Offer">
      <Text style={{ fontFamily: f.bold, fontSize: 24 }}>Share Castly. Grow your portfolio.</Text>
      <Text>
        Publish a public promotional video about Castly on your own social account, then submit its
        post URL. Our team manually checks that the post belongs to you and promotes Castly. One
        offer per creator and one claim per post.
      </Text>
      <Text>
        After approval, you receive Creator Pro for 12 months from the approval date. This offer
        does not renew automatically. Submission alone does not activate membership.
      </Text>
      {error ? (
        <Text accessibilityRole="alert" style={{ color: c.error }}>
          {error}
        </Text>
      ) : null}
      {claims.map((claim) => (
        <View
          key={claim.id}
          style={{ backgroundColor: c.lavender, padding: 20, borderRadius: 20, gap: 10 }}
        >
          <Text style={{ fontFamily: f.bold }}>Status: {claim.status}</Text>
          <Text selectable>{claim.post_url}</Text>
          {claim.reason ? (
            <Text>{claim.reason}</Text>
          ) : (
            <Text>Your post is waiting for manual review.</Text>
          )}
        </View>
      ))}
      {!claims.length ? (
        <>
          <Field
            label="Public promotional post URL"
            value={url}
            onChangeText={setUrl}
            placeholder="https://www.instagram.com/reel/…"
            autoCapitalize="none"
          />
          <PrimaryButton
            title={busy ? 'Please wait…' : 'Submit for review'}
            disabled={busy || !session}
            onPress={() => void submit()}
          />
        </>
      ) : null}
      <PrimaryButton title="Refresh status" disabled={busy} onPress={() => void refresh()} />
    </Screen>
  );
}

export default function FoundingOfferScreen() {
  const { session } = useAuth();
  return <FoundingContent key={session?.user.id || 'guest'} />;
}
