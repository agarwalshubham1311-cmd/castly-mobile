import React from 'react';
import { View, Text } from 'react-native';
import { router } from 'expo-router';
import { useNotifications } from '../backend/NotificationProvider';
import { IconButton } from './ui';
import { c, f } from '../theme';
export function NotificationBell() {
  const { stats } = useNotifications();
  const count = stats.enabled ? stats.unread_count : 0;
  return (
    <View>
      <IconButton
        name="notifications-outline"
        label={count ? `Notifications, ${count} unread` : 'Notifications'}
        onPress={() => router.push('/notifications')}
      />
      {count > 0 && (
        <View
          pointerEvents="none"
          style={{
            position: 'absolute',
            top: 0,
            right: 0,
            borderRadius: 12,
            minWidth: 20,
            padding: 3,
            backgroundColor: c.primaryDark,
            alignItems: 'center',
          }}
        >
          <Text style={{ fontFamily: f.bold, color: c.white, fontSize: 10 }}>
            {count > 99 ? '99+' : count}
          </Text>
        </View>
      )}
    </View>
  );
}
