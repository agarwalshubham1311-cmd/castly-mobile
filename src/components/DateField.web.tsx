import React, { useId } from 'react';
import { Text, View } from 'react-native';
import { localDateISO } from '../dates';
import { c, f } from '../theme';
import type { DateFieldProps } from './DateField';
/** The browser supplies its keyboard-accessible calendar; unsupported browsers retain text fallback. */
export default function DateField({ value, onChange, error }: DateFieldProps) {
  const id = useId();
  return (
    <View style={{ gap: 7 }}>
      <label htmlFor={id} style={{ fontFamily: f.semibold, fontSize: 12, color: c.text }}>
        Proposed date
      </label>
      <input
        id={id}
        aria-label="Proposed date"
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : `${id}-hint`}
        type="date"
        min={localDateISO()}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        style={{
          boxSizing: 'border-box',
          width: '100%',
          minHeight: 48,
          border: `1px solid ${error ? c.error : c.border}`,
          borderRadius: 12,
          background: '#F7F4FC',
          padding: 12,
          color: c.text,
          fontFamily: f.regular,
          fontSize: 14,
          colorScheme: 'light',
        }}
      />
      <Text
        nativeID={`${id}-hint`}
        style={{ fontFamily: f.regular, fontSize: 11, color: c.secondary }}
      >
        Choose a calendar date. If your browser has no picker, use YYYY-MM-DD.
      </Text>
      {error && (
        <Text
          nativeID={`${id}-error`}
          accessibilityRole="alert"
          style={{ fontFamily: f.medium, fontSize: 12, color: c.error }}
        >
          {error}
        </Text>
      )}
    </View>
  );
}
