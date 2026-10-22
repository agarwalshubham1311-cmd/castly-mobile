import React, { useState } from 'react';
import { View, Text } from 'react-native';
import { Redirect, router } from 'expo-router';
import { useAuth } from './AuthProvider';
import { readableError } from '../backend/errors';
import type { Role } from '../backend/types';
import { c, f } from '../theme';
import {
  CategoryChip,
  Chips,
  Field,
  Heading,
  Label,
  PrimaryButton,
  Screen,
} from '../components/ui';
export function AuthScreen({ signup = false }: { signup?: boolean }) {
  const auth = useAuth();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirmation, setConfirmation] = useState(false);
  if (auth.mode === 'invalid')
    return (
      <Screen title="Configuration required">
        <Label accessibilityRole="alert">{auth.error}</Label>
      </Screen>
    );
  if (auth.mode === 'mock')
    return (
      <Screen title="Castly preview">
        <Heading>Explore the local demo</Heading>
        <Label>
          No Supabase project is configured. No account is created or password saved in this
          preview.
        </Label>
        <PrimaryButton title="Continue demo" onPress={() => router.replace('/')} />
      </Screen>
    );
  if (auth.loading)
    return (
      <Screen title="Your account">
        <Label>Restoring your account…</Label>
      </Screen>
    );
  if (auth.session)
    return <Redirect href={auth.account?.primary_role ? '/account' : '/onboarding'} />;
  const submit = async () => {
    setError('');
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError('Enter a valid email address.');
      return;
    }
    if (signup && (name.trim().length < 2 || name.trim().length > 100)) {
      setError('Enter a name of 2–100 characters.');
      return;
    }
    if (password.length < (signup ? 8 : 1)) {
      setError(signup ? 'Use a password of at least 8 characters.' : 'Enter your password.');
      return;
    }
    setBusy(true);
    try {
      if (signup) {
        const result = await auth.signup(email, password, name);
        if (!result.session) {
          setConfirmation(true);
          setPassword('');
        }
      } else await auth.login(email, password);
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title={signup ? 'Create account' : 'Welcome back'}>
      <Text style={{ fontFamily: f.extrabold, fontSize: 32, color: c.text }}>
        castly<Text style={{ color: c.primary }}>✦</Text>
      </Text>
      <Heading>{signup ? 'Your next creative chapter' : 'Find your creative connection'}</Heading>
      <Label>
        {signup
          ? 'Create an account, then choose Client or Talent as your primary role.'
          : 'Sign in to manage your Castly account.'}
      </Label>
      {confirmation ? (
        <View style={{ gap: 16 }}>
          <Heading>Check your email</Heading>
          <Label>
            If this address is eligible, a confirmation email will arrive. Confirm your address,
            then return here to sign in. No role is assigned until onboarding.
          </Label>
          <PrimaryButton title="Go to sign in" onPress={() => router.replace('/login')} />
        </View>
      ) : (
        <>
          {signup && (
            <Field
              label="Your name"
              value={name}
              onChangeText={setName}
              autoComplete="name"
              maxLength={100}
            />
          )}
          <Field
            label="Email"
            value={email}
            onChangeText={setEmail}
            keyboardType="email-address"
            autoCapitalize="none"
            autoComplete="email"
          />
          <Field
            label="Password"
            value={password}
            onChangeText={setPassword}
            secureTextEntry
            autoComplete={signup ? 'new-password' : 'current-password'}
            autoCapitalize="none"
          />
          {!!error && (
            <Label accessibilityRole="alert" style={{ color: c.error }}>
              {error}
            </Label>
          )}
          {!!auth.error && (
            <Label accessibilityRole="alert" style={{ color: c.error }}>
              {auth.error}
            </Label>
          )}
          <PrimaryButton
            title={busy ? 'Please wait…' : signup ? 'Create account' : 'Sign in'}
            disabled={busy}
            onPress={() => {
              void submit();
            }}
          />
          <PrimaryButton
            title={signup ? 'Already have an account? Sign in' : 'New to Castly? Create account'}
            secondary
            disabled={busy}
            onPress={() => router.replace(signup ? '/login' : '/signup')}
          />
        </>
      )}
      <PrimaryButton title="Browse talent" secondary onPress={() => router.replace('/')} />
    </Screen>
  );
}
export function OnboardingScreen() {
  const auth = useAuth();
  const [role, setRole] = useState<Role | null>(null);
  const [name, setName] = useState(auth.account?.display_name || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (auth.mode === 'mock') return <Redirect href="/account" />;
  if (auth.loading)
    return (
      <Screen title="Your account">
        <Label>Loading your account…</Label>
      </Screen>
    );
  if (!auth.session) return <Redirect href="/login" />;
  if (auth.error)
    return (
      <Screen title="Account unavailable">
        <Label accessibilityRole="alert">{auth.error}</Label>
        <PrimaryButton title="Retry account" onPress={auth.refresh} />
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
  if (auth.account?.primary_role) return <Redirect href="/account" />;
  const submit = async () => {
    setError('');
    if (!role || name.trim().length < 2 || name.trim().length > 100) {
      setError('Choose a role and enter a name of 2–100 characters.');
      return;
    }
    setBusy(true);
    try {
      await auth.onboard(role, name);
      router.replace('/account');
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title="Make Castly yours">
      <Heading>How will you use Castly?</Heading>
      <Label>Choose one primary role. This choice cannot be changed later.</Label>
      <Field label="Account name" value={name} onChangeText={setName} maxLength={100} />
      <Chips>
        {(['client', 'talent'] as const).map((value) => (
          <CategoryChip
            key={value}
            title={value === 'client' ? 'Client' : 'Talent'}
            active={role === value}
            onPress={() => setRole(value)}
          />
        ))}
      </Chips>
      <Label>
        {role === 'talent'
          ? 'Create and publish a talent profile and portfolio.'
          : 'Discover talent and build a private shortlist.'}
      </Label>
      {!!error && (
        <Label accessibilityRole="alert" style={{ color: c.error }}>
          {error}
        </Label>
      )}
      <PrimaryButton
        title={busy ? 'Saving…' : 'Continue'}
        disabled={busy}
        onPress={() => {
          void submit();
        }}
      />
    </Screen>
  );
}
