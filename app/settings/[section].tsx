import React from 'react';
import { Settings as Component } from '../../src/screens/Account';
import { AuthGuard } from '../../src/auth/Guard';
export default function Route() {
  return <AuthGuard>{<Component />}</AuthGuard>;
}
