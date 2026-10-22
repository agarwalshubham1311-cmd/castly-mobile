import React from 'react';
import { Pressable, Text } from 'react-native';
import { Icon, type IconName } from './ui';
import { c, f } from '../theme';
export function AccountRow({
  title,
  icon,
  onPress,
  disabled = false,
}: {
  title: string;
  icon: IconName;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      disabled={disabled}
      onPress={onPress}
      style={{
        minHeight: 54,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
        borderBottomWidth: 1,
        borderColor: c.border,
        paddingHorizontal: 12,
      }}
    >
      <Icon name={icon} size={19} />
      <Text style={{ flex: 1, fontFamily: f.medium, color: c.text, fontSize: 13 }}>{title}</Text>
      <Icon name="chevron-forward" size={15} color={c.secondary} />
    </Pressable>
  );
}
