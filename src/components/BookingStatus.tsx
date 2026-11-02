import React from 'react';
import { Text, View } from 'react-native';
import { c, f } from '../theme';
import type { BookingRow } from '../backend/types';
export function BookingStatus({ status }: { status: BookingRow['status'] }) {
  return (
    <View
      style={{
        alignSelf: 'flex-start',
        backgroundColor: c.lavender,
        borderRadius: 14,
        paddingHorizontal: 12,
        paddingVertical: 6,
      }}
    >
      <Text
        accessibilityLabel={`Booking status: ${status}`}
        style={{ fontFamily: f.semibold, color: c.primaryDark, fontSize: 12 }}
      >
        {status[0].toUpperCase() + status.slice(1)}
      </Text>
    </View>
  );
}
