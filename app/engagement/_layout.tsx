import React from 'react';
import { Stack } from 'expo-router';
import { AuthGuard } from '../../src/auth/Guard';
export default function Layout() { return <AuthGuard><Stack screenOptions={{ headerShown: false }} /></AuthGuard>; }
