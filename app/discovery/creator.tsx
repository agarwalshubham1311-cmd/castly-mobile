import React from 'react';
import { AuthGuard } from '../../src/auth/Guard';
import CreatorDiscoveryScreen from '../../src/discovery/CreatorDiscoveryScreen';
export default function CreatorDiscovery() {
  return <AuthGuard role="talent"><CreatorDiscoveryScreen /></AuthGuard>;
}
