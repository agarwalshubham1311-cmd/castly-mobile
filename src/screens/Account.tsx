import { AccountRow } from '../components/AccountRow';
import { useAuth } from '../auth/AuthProvider';
import ConnectedAccount, { ConnectedSettings } from './ConnectedAccount';
import AccessibleSwitch from '../components/AccessibleSwitch';
import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useDemo } from '../store';
import { talentMedia } from '../talentMedia';
import { TalentImage } from '../TalentImage';
import { c, f } from '../theme';
import {
  EmptyState,
  Field,
  Heading,
  Icon,
  IconButton,
  Label,
  PrimaryButton,
  SampleNote,
  Screen,
} from '../components/ui';
export default function Account() {
  const auth = useAuth();
  return auth.mode === 'mock' ? <MockAccount /> : <ConnectedAccount />;
}
function MockAccount() {
  const { saved, bookings } = useDemo();
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
        <TalentImage
          source={talentMedia['portrait-03']}
          label="Sample account portrait"
          style={{ height: 60, width: 60, borderRadius: 30 }}
        />
        <View style={{ flex: 1 }}>
          <Heading>Creative Studio</Heading>
          <Label>Demo business account</Label>
        </View>
      </View>
      <SampleNote text="Fictional workspace · counts reflect sample & local activity" />
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {[
          [bookings.length, 'Bookings'],
          [saved.length, 'Saved'],
          [bookings.filter((b) => b.status !== 'Completed').length, 'Open projects'],
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
      <View style={{ backgroundColor: c.white, borderRadius: 16, overflow: 'hidden' }}>
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
      </View>
      <View style={{ backgroundColor: c.lavender, borderRadius: 20, padding: 20, gap: 10 }}>
        <Heading>Your next collaboration starts here.</Heading>
        <Label>Explore extraordinary people and build your creative shortlist.</Label>
        <PrimaryButton title="Discover talent" onPress={() => router.push('/')} />
      </View>
    </Screen>
  );
}
export function Settings() {
  const auth = useAuth();
  return auth.mode === 'mock' ? <MockSettings /> : <ConnectedSettings />;
}
function MockSettings() {
  const { section } = useLocalSearchParams<{ section: string }>();
  const [name, setName] = useState('Creative Studio');
  const [saved, setSaved] = useState(false);
  if (section === 'subscription')
    return (
      <Screen title="Subscription">
        <View style={{ padding: 22, backgroundColor: c.lavender, borderRadius: 22, gap: 16 }}>
          <Icon name="sparkles-outline" color={c.primaryDark} size={30} />
          <Heading>A space for creative connections</Heading>
          <Label>
            Castly is designed as a subscription-only marketplace. Booking commissions and mandatory
            booking payments are not part of this experience.
          </Label>
          <SampleNote text="Preview only · no active plan, checkout or billing integration" />
        </View>
        <Label>
          Subscription options will be introduced in a separately approved phase. No purchase or
          payment action is available.
        </Label>
      </Screen>
    );
  if (section === 'help')
    return (
      <Screen title="Help & support">
        <Heading>About this preview</Heading>
        <Label>
          Talent names, rates, conversations and bookings are fictional examples. Stock models do
          not represent the named talent.
        </Label>
        <Heading>What is saved?</Heading>
        <Label>
          Your talent shortlist is stored locally on this device. Messages, booking drafts and
          account-setting previews last only for the current session.
        </Label>
        <Heading>Was my request sent?</Heading>
        <Label>
          No. Booking and messaging actions are local demonstrations. There is no connected backend,
          recipient or payment service.
        </Label>
        <Heading>Can I manage a subscription?</Heading>
        <Label>Billing is not enabled. No charges can be made in this preview.</Label>
      </Screen>
    );
  if (section !== 'account')
    return (
      <Screen title="Settings">
        <EmptyState
          title="Setting not available"
          detail="This setting is outside the current frontend preview."
        />
      </Screen>
    );
  return (
    <Screen title="Account settings">
      <SampleNote text="Local form preview · no account update is sent" />
      <Field
        label="Workspace name"
        value={name}
        onChangeText={(value) => {
          setName(value);
          setSaved(false);
        }}
      />
      <PrimaryButton
        title="Preview update"
        disabled={!name.trim()}
        onPress={() => setSaved(true)}
      />
      {saved && (
        <Label style={{ color: c.success }}>
          Preview updated to “{name}”. This form is local only and does not change a real account.
        </Label>
      )}
    </Screen>
  );
}
export function Notifications() {
  const [enabled, setEnabled] = useState(true);
  return (
    <Screen title="Notifications">
      <SampleNote text="Notification preview · no push service is connected" />
      <View
        style={{
          backgroundColor: c.white,
          borderRadius: 16,
          padding: 16,
          flexDirection: 'row',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <View style={{ flex: 1 }}>
          <Heading>Demo notifications</Heading>
          <Label>Toggle this local preview preference.</Label>
        </View>
        <AccessibleSwitch label="Demo notifications" value={enabled} onValueChange={setEnabled} />
      </View>
      <EmptyState
        title={enabled ? 'You’re all caught up' : 'Demo notifications paused'}
        detail="No live notifications are available. Bookings and messages in this app are local demonstrations."
      />
    </Screen>
  );
}
