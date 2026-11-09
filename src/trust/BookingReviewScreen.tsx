import React, { useEffect, useState } from 'react';
import { useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
import type { BookingRow } from '../backend/types';
export default function BookingReviewScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [completion, setCompletion] = useState<{
    booking: BookingRow;
    confirmed_by_me: boolean;
    confirmation_count: number;
  } | null>(null);
  const [rating, setRating] = useState('5'),
    [body, setBody] = useState(''),
    [review, setReview] = useState<{ id: string; editable_until: string } | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  async function load() {
    const valid = begin();
    try {
      const [state, own] = await Promise.all([
        services!.trust.completion(id),
        services!.trust.ownReview(id),
      ]);
      if (valid()) {
        setCompletion(state);
        if (own[0]) {
          setReview(own[0]);
          setRating(String(own[0].rating));
          setBody(own[0].body);
        }
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Review unavailable.');
    }
  }
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  async function act(confirm: boolean) {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      if (!session) return;
      if (confirm) await services!.trust.confirmCompletion(id, session.user.id);
      else if (review)
        await services!.trust.editReview(review.id, Number(rating), body, session.user.id);
      else await services!.trust.submitReview(id, Number(rating), body, session.user.id);
      if (valid()) await load();
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  return (
    <Screen title="Booking completion and review">
      <Label>
        Confirm only when this project was genuinely completed. Both participants must confirm;
        scheduled dates do not count as completion. This does not confirm payment.
      </Label>
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {!completion ? (
        <Label>Loading booking…</Label>
      ) : (
        <>
          <Label>
            Status: {completion.booking.status} · Confirmations: {completion.confirmation_count} of
            2
          </Label>
          {completion.booking.status === 'accepted' ? (
            <PrimaryButton
              title={
                completion.confirmed_by_me
                  ? 'Waiting for the other participant'
                  : 'I confirm this project was completed'
              }
              disabled={busy || completion.confirmed_by_me}
              onPress={() => void act(true)}
            />
          ) : null}
          {completion.booking.status === 'completed' ? (
            <>
              <Label>
                Reviews reflect this completed booking. Avoid personal or confidential information.
              </Label>
              <Field
                label="Rating (1 to 5)"
                value={rating}
                onChangeText={setRating}
                keyboardType="number-pad"
              />
              <Field
                label="Written review (optional)"
                value={body}
                onChangeText={setBody}
                multiline
                maxLength={2000}
              />
              {review ? (
                <Label>
                  Edit available until {new Date(review.editable_until).toLocaleString()}
                </Label>
              ) : null}
              <PrimaryButton
                title={review ? 'Save review changes' : 'Submit booking review'}
                disabled={
                  busy ||
                  !/^[1-5]$/.test(rating) ||
                  (!!review && new Date(review.editable_until).getTime() <= Date.now())
                }
                onPress={() => void act(false)}
              />
            </>
          ) : null}
        </>
      )}
      <PrimaryButton
        secondary
        title="Refresh booking and review"
        disabled={busy}
        onPress={() => void load()}
      />
    </Screen>
  );
}
