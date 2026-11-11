import { safetyConfiguration } from './configuration';
import React, { useEffect, useState } from 'react';
import { router } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
export function TrustLinks({ bookingId, talentId }: { bookingId?: string; talentId?: string }) {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [enabled, setEnabled] = useState(false);
  const [disputes, setDisputes] = useState(false);
  useEffect(() => {
    const valid = begin();
    void safetyConfiguration(session?.user.id || '')
      .then((config) => {
        if (valid()) {
          setEnabled(config.features.reviews);
          setDisputes(config.features.disputes);
        }
      })
      .catch(() => {});
  }, [session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!enabled && !disputes) return null;
  return (
    <>
      {enabled ? (
        <PrimaryButton
          secondary
          title={bookingId ? 'Completion and review' : 'Booking-based reviews'}
          onPress={() =>
            router.push(bookingId ? `/safety/booking/${bookingId}` : `/safety/reviews/${talentId}`)
          }
        />
      ) : null}
      {bookingId && disputes ? (
        <PrimaryButton
          secondary
          title="Open a booking dispute"
          onPress={() =>
            router.push({ pathname: '/safety/disputes', params: { booking: bookingId } })
          }
        />
      ) : null}
    </>
  );
}
