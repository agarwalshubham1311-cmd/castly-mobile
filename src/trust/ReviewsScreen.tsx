import { SafetyActions } from './SafetyActions';
import React, { useEffect, useState } from 'react';
import { useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Screen, Label, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
export default function ReviewsScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { session } = useAuth();
  const [page, setPage] = useState(0),
    [data, setData] = useState<Awaited<
      ReturnType<NonNullable<typeof services>['trust']['reviews']>
    > | null>(null),
    [error, setError] = useState('');
  const begin = useTrustGuard(`${session?.user.id}:${id}:${page}`);
  async function load() {
    const valid = begin();
    setError('');
    try {
      const next = await services!.trust.reviews(id, page * 25);
      if (valid()) setData(next);
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Reviews unavailable.');
    }
  }
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [id, page]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Screen title="Booking-based reviews">
      <Label>
        Only participants in projects confirmed complete by both parties may review. Reviews and
        verification are not guarantees of safety.
      </Label>
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {data ? (
        <>
          <Label>
            {data.summary.review_count} reviews · {data.summary.average_rating ?? 'No rating yet'}
          </Label>
          {data.reviews.map((item) => (
            <React.Fragment key={item.id}>
              <Label>
                {item.rating} / 5 · {new Date(item.created_at).toLocaleDateString()}
              </Label>
              <Label>{item.body || 'No written review'}</Label>
              <SafetyActions kind="review" id={item.id} />
            </React.Fragment>
          ))}
          {!data.reviews.length ? <Label>No visible reviews on this page.</Label> : null}
          <PrimaryButton
            title="Previous reviews"
            disabled={page === 0}
            onPress={() => setPage(page - 1)}
          />
          <PrimaryButton
            title="More reviews"
            disabled={data.reviews.length < 25}
            onPress={() => setPage(page + 1)}
          />
        </>
      ) : (
        <Label>Loading reviews…</Label>
      )}
      <PrimaryButton secondary title="Refresh reviews" onPress={() => void load()} />
    </Screen>
  );
}
