import React from 'react';
import { Slot } from 'expo-router';
import { AuthGuard } from '../../src/auth/Guard';
export default function SubscriptionLayout() {
  return (
    <AuthGuard>
      <Slot />
    </AuthGuard>
  );
}
