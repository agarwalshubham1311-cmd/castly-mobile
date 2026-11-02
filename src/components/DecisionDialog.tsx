import React from 'react';
import { Modal, Pressable, Text, View, ScrollView } from 'react-native';
import { c, f } from '../theme';
export type Decision = 'accept' | 'reject' | 'cancel';
export function DecisionDialog({
  decision,
  busy,
  error,
  disabled = false,
  onConfirm,
  onClose,
}: {
  decision: Decision | null;
  busy: boolean;
  error?: string;
  disabled?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  if (!decision) return null;
  const descriptions = {
    accept:
      'Accepting makes a conversation available to both booking participants. No payment is collected.',
    reject: 'This request will be declined. It cannot be reopened.',
    cancel:
      'This booking will be cancelled. Messaging pauses if no other accepted booking exists for this pair.',
  };
  return (
    <Modal
      transparent
      visible
      animationType="fade"
      onRequestClose={() => {
        if (!busy) onClose();
      }}
    >
      <ScrollView
        style={{ flex: 1, backgroundColor: '#17103866' }}
        contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 24 }}
      >
        <View
          accessibilityViewIsModal
          accessibilityRole="alert"
          style={{
            backgroundColor: c.background,
            borderRadius: 22,
            padding: 22,
            gap: 14,
            width: '100%',
            maxWidth: 480,
            alignSelf: 'center',
          }}
        >
          <Text
            style={{ fontFamily: f.bold, fontSize: 20, color: c.text }}
          >{`Confirm ${decision} booking`}</Text>
          <Text style={{ fontFamily: f.regular, fontSize: 14, lineHeight: 22, color: c.text }}>
            {descriptions[decision]}
          </Text>
          {!!error && (
            <Text
              accessibilityRole="alert"
              style={{ fontFamily: f.regular, color: c.error, fontSize: 13, lineHeight: 21 }}
            >
              {error}
            </Text>
          )}
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Confirm ${decision} booking`}
            disabled={busy || disabled}
            accessibilityState={{ disabled: busy || disabled }}
            onPress={onConfirm}
            style={{
              minHeight: 48,
              borderRadius: 24,
              backgroundColor: c.primaryDark,
              justifyContent: 'center',
              alignItems: 'center',
              padding: 12,
            }}
          >
            <Text style={{ fontFamily: f.semibold, color: c.white }}>
              {busy ? 'Please wait…' : `Confirm ${decision}`}
            </Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Go back without changes"
            disabled={busy}
            accessibilityState={{ disabled: busy }}
            onPress={onClose}
            style={{ minHeight: 48, justifyContent: 'center', alignItems: 'center' }}
          >
            <Text style={{ fontFamily: f.semibold, color: c.primaryDark }}>Go back</Text>
          </Pressable>
        </View>
      </ScrollView>
    </Modal>
  );
}
