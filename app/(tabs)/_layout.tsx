import React from 'react';
import { Tabs } from 'expo-router';
import { useNotifications } from '../../src/backend/NotificationProvider';
import { BottomNavigation } from '../../src/components/ui';
export default function TabLayout() {
  const { stats } = useNotifications();
  return (
    <Tabs
      screenOptions={{ headerShown: false, tabBarHideOnKeyboard: true }}
      tabBar={(props) => <BottomNavigation {...props} />}
    >
      <Tabs.Screen name="index" options={{ title: 'Home' }} />
      <Tabs.Screen name="search" options={{ title: 'Search' }} />
      <Tabs.Screen name="bookings" options={{ title: 'Bookings' }} />
      <Tabs.Screen
        name="messages"
        options={{
          title: 'Messages',
          tabBarBadge:
            stats.enabled && stats.message_unread_count > 0
              ? Math.min(stats.message_unread_count, 99)
              : undefined,
        }}
      />
      <Tabs.Screen name="account" options={{ title: 'Profile' }} />
    </Tabs>
  );
}
