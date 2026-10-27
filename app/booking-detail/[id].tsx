import { LiveBookingDetail } from '../../src/screens/LiveActivity';
import { businessMode } from '../../src/backend';
import React from 'react';
import { BookingDetail as Component } from '../../src/screens/Activity';
import { AuthGuard } from '../../src/auth/Guard';
import { useAuth } from '../../src/auth/AuthProvider';
import { Screen, EmptyState } from '../../src/components/ui';
export default function Route() {
  const auth = useAuth();
  return (
    <AuthGuard>
      {auth.mode === 'mock' ? (
        <Component />
      ) : businessMode === 'spring' ? (
        <LiveBookingDetail />
      ) : (
        <Screen title="Booking details">
          <EmptyState
            title="Booking details are not available yet"
            detail="Your account and shortlist are available. No booking request or message will be sent from this preview."
          />
        </Screen>
      )}
    </AuthGuard>
  );
}
