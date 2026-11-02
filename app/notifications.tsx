import React from 'react';
import { Notifications as Component } from '../src/screens/Account';
import { AuthGuard } from '../src/auth/Guard';
import { useAuth } from '../src/auth/AuthProvider';
import LiveNotifications from '../src/screens/LiveNotifications';
import { businessMode } from '../src/backend';
import { Screen, EmptyState } from '../src/components/ui';
export default function Route() {
  const auth = useAuth();
  return (
    <AuthGuard>
      {auth.mode === 'mock' ? (
        <Component />
      ) : businessMode === 'spring' ? (
        <LiveNotifications />
      ) : (
        <Screen title="Notifications">
          <EmptyState
            title="Notifications unavailable"
            detail="In-app notifications require Spring business mode and approved notification storage."
          />
        </Screen>
      )}
    </AuthGuard>
  );
}
