import React from 'react';
import Component from '../../src/screens/Booking';
import { AuthGuard } from '../../src/auth/Guard';
import { useAuth } from '../../src/auth/AuthProvider';
import { Screen, EmptyState } from '../../src/components/ui';
export default function Route() {
  const auth = useAuth();
  return (
    <AuthGuard>
      {auth.mode === 'mock' ? (
        <Component />
      ) : (
        <Screen title="Request booking">
          <EmptyState
            title="Booking requests are not available yet"
            detail="Your account and shortlist are available. No booking request or message will be sent from this preview."
          />
        </Screen>
      )}
    </AuthGuard>
  );
}
