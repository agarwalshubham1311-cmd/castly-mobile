import React, { useState } from 'react';
import { FlatList, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useDemo } from '../store';
import { talents, money } from '../talent';
import { c, f } from '../theme';
import {
  BookingCard,
  CategoryChip,
  Chips,
  EmptyState,
  Heading,
  Label,
  MessageCard,
  PrimaryButton,
  SampleNote,
  Screen,
  SearchBar,
  useLayout,
} from '../components/ui';
export function Bookings() {
  const { bookings } = useDemo();
  const [tab, setTab] = useState('All');
  const { padding } = useLayout();
  const results = bookings.filter((b) => tab === 'All' || b.status === tab);
  return (
    <Screen title="Bookings" back={false} scroll={false}>
      <FlatList
        data={results}
        keyExtractor={(b) => b.id}
        contentContainerStyle={{ paddingHorizontal: padding, paddingBottom: 24, gap: 12 }}
        ListHeaderComponent={
          <View style={{ gap: 12, marginBottom: 4 }}>
            <Chips>
              {['All', 'Pending', 'Confirmed', 'Completed'].map((value) => (
                <CategoryChip
                  key={value}
                  title={value}
                  active={value === tab}
                  onPress={() => setTab(value)}
                />
              ))}
            </Chips>
            <SampleNote text="Sample activity & local drafts · no real bookings" />
          </View>
        }
        renderItem={({ item }) => {
          const talent = talents.find((t) => t.id === item.talentId);
          return talent ? <BookingCard booking={item} talent={talent} /> : null;
        }}
        ListEmptyComponent={
          <EmptyState
            title="Nothing here yet"
            detail="Local requests and sample activity matching this status will appear here."
          />
        }
      />
    </Screen>
  );
}
export function BookingDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { bookings, openConversation } = useDemo();
  const booking = bookings.find((b) => b.id === id);
  const talent = talents.find((t) => t.id === booking?.talentId);
  if (!booking || !talent)
    return (
      <Screen title="Booking details">
        <EmptyState
          title="Request not found"
          detail="Local requests last for this demo session. They are not sent to a server."
          action="View bookings"
          onPress={() => router.replace('/bookings')}
        />
      </Screen>
    );
  return (
    <Screen title="Booking details">
      <SampleNote
        text={
          booking.local
            ? 'Local draft · not sent · this session only'
            : 'Fictional booking fixture · no real reservation'
        }
      />
      <View style={{ backgroundColor: c.white, padding: 20, borderRadius: 20, gap: 14 }}>
        <Heading>{booking.project}</Heading>
        <Label style={{ color: c.primaryDark }}>
          {booking.local ? 'Local request draft' : `${booking.status} · sample status`}
        </Label>
        {[
          ['Talent', talent.name],
          ['Proposed date', booking.date],
          ['Location', booking.location],
          ['Indicative budget', money(Number(booking.budget))],
        ].map(([label, value]) => (
          <View key={label} style={{ gap: 3 }}>
            <Label>{label}</Label>
            <Text style={{ fontFamily: f.semibold, color: c.text, fontSize: 14 }}>{value}</Text>
          </View>
        ))}
        <Label>Project requirements</Label>
        <Label style={{ color: c.text }}>{booking.requirements}</Label>
      </View>
      <Label>No payment, commission or booking confirmation is processed in this preview.</Label>
      <PrimaryButton
        title="View talent"
        secondary
        onPress={() => router.push(`/talent/${talent.id}`)}
      />
      <PrimaryButton
        title="Open local conversation"
        onPress={() => {
          openConversation(talent.id);
          router.push(`/chat/${talent.id}`);
        }}
      />
    </Screen>
  );
}
export function Messages() {
  const { conversations } = useDemo();
  const { padding } = useLayout();
  const [tab, setTab] = useState('All');
  const [query, setQuery] = useState('');
  const results = conversations.filter((conv) => {
    const talent = talents.find((t) => t.id === conv.talentId);
    return (
      (tab === 'Archived' ? conv.archived : !conv.archived) &&
      (tab !== 'Unread' || conv.unread) &&
      (tab !== 'Bookings' || conv.booking) &&
      `${talent?.name} ${conv.messages.at(-1)?.text ?? ''}`
        .toLowerCase()
        .includes(query.trim().toLowerCase())
    );
  });
  return (
    <Screen title="Messages" back={false} scroll={false}>
      <FlatList
        data={results}
        keyExtractor={(m) => m.id}
        contentContainerStyle={{ paddingHorizontal: padding, paddingBottom: 24, gap: 8 }}
        ListHeaderComponent={
          <View style={{ gap: 12, marginBottom: 4 }}>
            <SearchBar
              label="Search conversations"
              value={query}
              onChange={setQuery}
              placeholder="Search conversations"
            />
            <Chips>
              {['All', 'Unread', 'Bookings', 'Archived'].map((value) => (
                <CategoryChip
                  key={value}
                  title={value}
                  active={value === tab}
                  onPress={() => setTab(value)}
                />
              ))}
            </Chips>
            <SampleNote text="Local conversation demo · messages are never sent" />
          </View>
        }
        renderItem={({ item }) => {
          const talent = talents.find((t) => t.id === item.talentId);
          return talent ? <MessageCard conversation={item} talent={talent} /> : null;
        }}
        ListEmptyComponent={
          <EmptyState
            title="No conversations here"
            detail="Try another filter, or open a local conversation from a sample talent profile."
          />
        }
      />
    </Screen>
  );
}
