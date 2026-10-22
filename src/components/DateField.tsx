import React, { useState } from 'react';
import { Modal, Platform, Pressable, ScrollView, Text, View } from 'react-native';
import DateTimePicker, { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { SafeAreaView } from 'react-native-safe-area-context';
import { localDateISO, parseLocalDate, readableDate } from '../dates';
import { c, f } from '../theme';
import { Heading, Icon, PrimaryButton, styles } from './ui';
export type DateFieldProps = { value: string; onChange: (value: string) => void; error?: string };
export default function DateField({ value, onChange, error }: DateFieldProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(() => parseLocalDate(value) ?? new Date());
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const launch = () => {
    const selected = parseLocalDate(value) ?? new Date();
    if (Platform.OS === 'android')
      DateTimePickerAndroid.open({
        value: selected,
        mode: 'date',
        minimumDate: today,
        onChange: (event, date) => {
          if (event.type === 'set' && date) onChange(localDateISO(date));
        },
      });
    else {
      setDraft(selected);
      setOpen(true);
    }
  };
  return (
    <View style={{ gap: 7 }}>
      <Text style={styles.fieldLabel}>Proposed date</Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Proposed date: ${readableDate(value)}`}
        accessibilityHint="Opens the system calendar date selector"
        onPress={launch}
        style={[
          styles.field,
          { flexDirection: 'row', alignItems: 'center', gap: 10 },
          error && { borderColor: c.error },
        ]}
      >
        <Icon name="calendar-outline" color={c.primaryDark} />
        <Text style={{ color: value ? c.text : c.secondary, fontFamily: f.regular, fontSize: 13 }}>
          {readableDate(value)}
        </Text>
      </Pressable>
      {error && (
        <Text
          accessibilityRole="alert"
          style={{ fontFamily: f.medium, color: c.error, fontSize: 12 }}
        >
          {error}
        </Text>
      )}
      <Modal visible={open} animationType="slide" onRequestClose={() => setOpen(false)}>
        <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
          <ScrollView contentContainerStyle={{ padding: 20, gap: 20 }}>
            <Heading>Choose proposed date</Heading>
            <DateTimePicker
              accessibilityLabel="Proposed date"
              value={draft}
              minimumDate={today}
              mode="date"
              display="inline"
              onChange={(_, date) => {
                if (date) setDraft(date);
              }}
            />
            <PrimaryButton
              title="Use this date"
              onPress={() => {
                onChange(localDateISO(draft));
                setOpen(false);
              }}
            />
            <PrimaryButton title="Cancel date selection" secondary onPress={() => setOpen(false)} />
          </ScrollView>
        </SafeAreaView>
      </Modal>
    </View>
  );
}
