import React from 'react';
import { Switch, View } from 'react-native';
import { c } from '../theme';
export type AccessibleSwitchProps = {
  label: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
};
/** Keep the platform switch and give its touch slop room inside a 48dp row. */
export default function AccessibleSwitch({ label, value, onValueChange }: AccessibleSwitchProps) {
  return (
    <View style={{ minWidth: 56, minHeight: 48, alignItems: 'center', justifyContent: 'center' }}>
      <Switch
        accessibilityLabel={label}
        value={value}
        onValueChange={onValueChange}
        hitSlop={{ top: 14, bottom: 14, left: 8, right: 8 }}
        trackColor={{ false: c.border, true: c.primary }}
      />
    </View>
  );
}
