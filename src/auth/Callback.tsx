import React, { useEffect, useState, useRef } from 'react';
import { Redirect, useLocalSearchParams, router } from 'expo-router';
import { useAuth } from './AuthProvider';
import { services } from '../backend';
import { readableError } from '../backend/errors';
import { Label, PrimaryButton, Screen } from '../components/ui';
export default function AuthCallback() {
  const { code, error_description } = useLocalSearchParams<{
    code?: string;
    error_description?: string;
  }>();
  const auth = useAuth();
  const signedInUser = auth.session?.user.id;
  const [busy, setBusy] = useState(!!code && !signedInUser);
  const [error, setError] = useState(error_description || '');
  const exchange = useRef<{ code: string; promise: Promise<unknown> } | null>(null);
  useEffect(() => {
    let active = true;
    if (!code || !services || signedInUser) return;
    if (!exchange.current || exchange.current.code !== code)
      exchange.current = { code, promise: services.auth.exchangeCode(code) };
    exchange.current.promise
      .catch((e) => {
        if (active) setError(readableError(e));
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
    };
  }, [code, signedInUser]);
  if (auth.mode === 'mock') return <Redirect href="/login" />;
  if ((busy && !signedInUser) || auth.loading)
    return (
      <Screen title="Confirming account">
        <Label>Completing your email confirmation…</Label>
      </Screen>
    );
  if (auth.session && !error)
    return <Redirect href={auth.account?.primary_role ? '/account' : '/onboarding'} />;
  return (
    <Screen title="Email confirmation">
      <Label accessibilityRole={error ? 'alert' : undefined}>
        {error ||
          'Return to sign in after confirming your email. If you opened this link on another device, sign in there with your email and password.'}
      </Label>
      <PrimaryButton title="Go to sign in" onPress={() => router.replace('/login')} />
    </Screen>
  );
}
