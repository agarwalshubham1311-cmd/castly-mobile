import React, { useState } from 'react';
import { c } from '../theme';
import type { AccessibleSwitchProps } from './AccessibleSwitch';
/** A full-size native HTML input preserves Space, focus and screen-reader semantics. */
export default function AccessibleSwitch({ label, value, onValueChange }: AccessibleSwitchProps) {
  const [focused, setFocused] = useState(false);
  return (
    <span
      style={{
        position: 'relative',
        display: 'inline-flex',
        width: 56,
        height: 48,
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 40,
          height: 24,
          borderRadius: 12,
          background: value ? c.primary : c.secondary,
          position: 'relative',
          outline: focused ? `2px solid ${c.primaryDark}` : undefined,
          outlineOffset: 3,
        }}
      >
        <span
          style={{
            position: 'absolute',
            width: 20,
            height: 20,
            top: 2,
            left: value ? 18 : 2,
            borderRadius: 10,
            background: c.white,
          }}
        />
      </span>
      <input
        type="checkbox"
        role="switch"
        aria-label={label}
        aria-checked={value}
        checked={value}
        onChange={(event) => onValueChange(event.target.checked)}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={{
          position: 'absolute',
          inset: 0,
          width: 56,
          height: 48,
          margin: 0,
          opacity: 0,
          cursor: 'pointer',
        }}
      />
    </span>
  );
}
