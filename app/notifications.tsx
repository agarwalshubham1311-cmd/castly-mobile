import React from 'react';
import { Notifications as Component } from '../src/screens/Account';
import { AuthGuard } from '../src/auth/Guard';
import { useAuth } from '../src/auth/AuthProvider';
import { Screen, EmptyState } from '../src/components/ui';
export default function Route() {
  const auth = useAuth();
  return (
    <AuthGuard>
      {auth.mode === 'mock' ? (
        <Component />
      ) : (
        <Screen title="Notifications">
          <EmptyState
            title="No notifications yet"
            detail="Your account and shortlist are available. No booking request or message will be sent from this preview."
          />
        </Screen>
      )}
    </AuthGuard>
  );
}
