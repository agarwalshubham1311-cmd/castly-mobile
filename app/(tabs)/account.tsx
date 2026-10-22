import React from 'react';
import Component from '../../src/screens/Account';
import { AuthGuard } from '../../src/auth/Guard';
export default function Route() {
  return <AuthGuard>{<Component />}</AuthGuard>;
}
