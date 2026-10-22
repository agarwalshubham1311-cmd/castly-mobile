import React from 'react';
import { Redirect, router } from 'expo-router';
import { useAuth } from './AuthProvider';
import { EmptyState, Label, PrimaryButton, Screen } from '../components/ui';
import type { Role } from '../backend/types';
import { readableError } from '../backend/errors';
export function AuthGuard({ children, role }: { children: React.ReactNode; role?: Role }) {
  const auth = useAuth();
  const [error, setError] = React.useState('');
  if (auth.mode === 'mock') return <>{children}</>;
  if (auth.loading)
    return (
      <Screen title="Your account">
        <Label>Restoring your account…</Label>
      </Screen>
    );
  if (auth.error)
    return (
      <Screen title="Account unavailable">
        <EmptyState
          title="Unable to load your account"
          detail={auth.error}
          action="Retry account"
          onPress={auth.refresh}
        />
        <PrimaryButton
          title="Sign out"
          secondary
          onPress={() => {
            auth.logout().catch((e) => setError(readableError(e)));
          }}
        />
        {!!error && <Label accessibilityRole="alert">{error}</Label>}
      </Screen>
    );
  if (!auth.session) return <Redirect href="/login" />;
  if (!auth.account?.primary_role) return <Redirect href="/onboarding" />;
  if (role && auth.account.primary_role !== role)
    return (
      <Screen title="Your account">
        <EmptyState
          title="Talent account required"
          detail="Your primary role is Client. Talent profile editing requires a Talent account."
          action="View account"
          onPress={() => router.replace('/account')}
        />
      </Screen>
    );
  return <>{children}</>;
}
