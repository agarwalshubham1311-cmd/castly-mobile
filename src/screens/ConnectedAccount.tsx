import { AccountRow } from '../components/AccountRow';
import React, { useEffect, useState } from 'react';
import { View, Text } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { readableError } from '../backend/errors';
import { useDemo } from '../store';
import { c, f } from '../theme';
import {
  EmptyState,
  Field,
  Heading,
  Icon,
  IconButton,
  Label,
  PrimaryButton,
  Screen,
} from '../components/ui';
import type { Entitlement } from '../backend/types';
export default function ConnectedAccount() {
  const auth = useAuth();
  const { saved } = useDemo();
  const [counts, setCounts] = useState<{ bookings: number; open: number } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const load = () => {
    setCounts(null);
    setError('');
    services!.activity
      .bookings()
      .then((rows) =>
        setCounts({
          bookings: rows?.length || 0,
          open: rows?.filter((row) => ['requested', 'accepted'].includes(row.status)).length || 0,
        }),
      )
      .catch((e) => setError(readableError(e)));
  };
  useEffect(() => {
    let active = true;
    services!.activity
      .bookings()
      .then((rows) => {
        if (active)
          setCounts({
            bookings: rows?.length || 0,
            open: rows?.filter((row) => ['requested', 'accepted'].includes(row.status)).length || 0,
          });
      })
      .catch((e) => {
        if (active) setError(readableError(e));
      });
    return () => {
      active = false;
    };
  }, [auth.session?.user.id]);
  const logout = async () => {
    setBusy(true);
    setError('');
    try {
      await auth.logout();
      router.replace('/login');
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen
      title="Profile"
      back={false}
      action={
        <IconButton
          name="settings-outline"
          label="Account settings"
          onPress={() => router.push('/settings/account')}
        />
      }
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
        <View
          style={{
            height: 60,
            width: 60,
            borderRadius: 30,
            backgroundColor: c.lavender,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Icon name="person-outline" size={28} />
        </View>
        <View style={{ flex: 1 }}>
          <Heading>{auth.account?.display_name}</Heading>
          <Label>
            {auth.account?.primary_role === 'talent' ? 'Talent account' : 'Client account'}
          </Label>
          <Label>{auth.session?.user.email}</Label>
        </View>
      </View>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {[
          [counts?.bookings ?? '…', 'Bookings'],
          [saved.length, 'Saved'],
          [counts?.open ?? '…', 'Open projects'],
        ].map(([count, label]) => (
          <View
            key={label}
            style={{
              flex: 1,
              paddingVertical: 16,
              paddingHorizontal: 4,
              alignItems: 'center',
              borderRadius: 14,
              borderWidth: 1,
              borderColor: c.border,
              backgroundColor: '#F2ECFF',
              gap: 5,
            }}
          >
            <Text style={{ fontFamily: f.bold, color: c.text, fontSize: 20 }}>{count}</Text>
            <Label style={{ fontSize: 11 }}>{label}</Label>
          </View>
        ))}
      </View>
      {!!error && (
        <>
          <Label accessibilityRole="alert" style={{ color: c.error }}>
            {error}
          </Label>
          <PrimaryButton title="Retry account activity" secondary onPress={load} />
        </>
      )}
      <View style={{ backgroundColor: c.white, borderRadius: 16, overflow: 'hidden' }}>
        {auth.account?.primary_role === 'talent' && (
          <AccountRow
            title="Manage talent profile"
            icon="create-outline"
            onPress={() => router.push('/talent-edit')}
          />
        )}
        <AccountRow
          title="My bookings"
          icon="calendar-outline"
          onPress={() => router.push('/bookings')}
        />
        <AccountRow
          title="Saved talent"
          icon="heart-outline"
          onPress={() => router.push('/saved')}
        />
        <AccountRow
          title="Messages"
          icon="chatbubble-ellipses-outline"
          onPress={() => router.push('/messages')}
        />
        <AccountRow
          title="Subscription"
          icon="sparkles-outline"
          onPress={() => router.push('/settings/subscription')}
        />
        <AccountRow
          title="Notifications"
          icon="notifications-outline"
          onPress={() => router.push('/notifications')}
        />
        <AccountRow
          title="Help & support"
          icon="help-circle-outline"
          onPress={() => router.push('/settings/help')}
        />
        <AccountRow
          title={busy ? 'Signing out…' : 'Sign out'}
          icon="log-out-outline"
          disabled={busy}
          onPress={() => {
            void logout();
          }}
        />
      </View>
      <View style={{ backgroundColor: c.lavender, borderRadius: 20, padding: 20, gap: 10 }}>
        <Heading>Your next collaboration starts here.</Heading>
        <Label>Explore talent and build your creative shortlist.</Label>
        <PrimaryButton title="Discover talent" onPress={() => router.push('/')} />
      </View>
    </Screen>
  );
}
export function ConnectedSettings() {
  const { section } = useLocalSearchParams<{ section: string }>();
  const auth = useAuth();
  const [name, setName] = useState(auth.account?.display_name || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [entitlements, setEntitlements] = useState<Entitlement[] | null>(null);
  useEffect(() => {
    let active = true;
    if (section === 'subscription')
      services!.account
        .entitlements()
        .then((rows) => {
          if (active) setEntitlements(rows);
        })
        .catch((e) => {
          if (active) setError(readableError(e));
        });
    return () => {
      active = false;
    };
  }, [section]);
  if (section === 'subscription')
    return (
      <Screen title="Subscription">
        <Heading>Your entitlements</Heading>
        {error ? (
          <Label accessibilityRole="alert">{error}</Label>
        ) : entitlements === null ? (
          <Label>Loading entitlements…</Label>
        ) : !entitlements.length ? (
          <EmptyState
            title="No active entitlements"
            detail="No subscription has been granted to this account. Checkout and payments are not enabled."
          />
        ) : (
          entitlements.map((row) => (
            <Label key={row.id}>
              {row.entitlement} ·{' '}
              {row.valid_until ? `Valid until ${row.valid_until}` : 'No expiration'}
            </Label>
          ))
        )}
      </Screen>
    );
  if (section !== 'account')
    return (
      <Screen title="Help & support">
        <Label>
          Your account, talent profile and shortlist are saved to your account. Booking requests,
          messaging and payments are not available yet. Your saved talent is private.
        </Label>
      </Screen>
    );
  const save = async () => {
    setError('');
    setMessage('');
    if (name.trim().length < 2 || name.trim().length > 100) {
      setError('Enter a name of 2–100 characters.');
      return;
    }
    setBusy(true);
    try {
      await services!.account.rename(auth.session!.user.id, name);
      auth.refresh();
      setMessage('Account updated.');
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title="Account settings">
      <Field label="Account name" value={name} onChangeText={setName} maxLength={100} />
      <Label>Primary role: {auth.account?.primary_role}. This role cannot be changed.</Label>
      {!!error && (
        <Label accessibilityRole="alert" style={{ color: c.error }}>
          {error}
        </Label>
      )}
      {!!message && <Label>{message}</Label>}
      <PrimaryButton
        title={busy ? 'Saving…' : 'Save account'}
        disabled={busy}
        onPress={() => {
          void save();
        }}
      />
    </Screen>
  );
}
