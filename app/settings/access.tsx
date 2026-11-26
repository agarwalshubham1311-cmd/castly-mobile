import React from 'react';
import AccountAccess from '../../src/screens/AccountAccess';
import { AuthGuard } from '../../src/auth/Guard';
export default function Route() {
  return (
    <AuthGuard>
      <AccountAccess />
    </AuthGuard>
  );
}
