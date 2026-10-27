import { LiveBookings } from '../../src/screens/LiveActivity';
import { businessMode } from '../../src/backend';
import React from 'react';
import { Bookings as Component } from '../../src/screens/Activity';
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
        <LiveBookings />
      ) : (
        <Screen title="Bookings">
          <EmptyState
            title="Booking requests are not available yet"
            detail="Your account and shortlist are available. No booking request or message will be sent from this preview."
          />
        </Screen>
      )}
    </AuthGuard>
  );
}
