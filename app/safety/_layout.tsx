import React from 'react';
import { Slot, usePathname, useGlobalSearchParams } from 'expo-router';
import { AuthGuard } from '../../src/auth/Guard';
export default function Layout() {
  const path = usePathname() + JSON.stringify(useGlobalSearchParams());
  return (
    <AuthGuard>
      <Slot key={path} />
    </AuthGuard>
  );
}
