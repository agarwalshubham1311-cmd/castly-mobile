import React from 'react';
import { router } from 'expo-router';
import { EmptyState, Screen } from '../src/components/ui';
export default function NotFound() {
  return (
    <Screen title="Page not found">
      <EmptyState
        title="Let’s find your way back"
        detail="This page is not part of the Castly preview."
        action="Go home"
        onPress={() => router.replace('/')}
      />
    </Screen>
  );
}
