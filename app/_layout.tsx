import React from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { Text, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { useFonts } from 'expo-font';
import { DemoProvider } from '../src/store';
import { c } from '../src/theme';
import { AuthProvider } from '../src/auth/AuthProvider';
import { CatalogProvider } from '../src/backend/CatalogProvider';
import { configuration } from '../src/backend';
export default function RootLayout() {
  const [loaded, error] = useFonts({
    Manrope_400Regular: require('@expo-google-fonts/manrope/400Regular/Manrope_400Regular.ttf'),
    Manrope_500Medium: require('@expo-google-fonts/manrope/500Medium/Manrope_500Medium.ttf'),
    Manrope_600SemiBold: require('@expo-google-fonts/manrope/600SemiBold/Manrope_600SemiBold.ttf'),
    Manrope_700Bold: require('@expo-google-fonts/manrope/700Bold/Manrope_700Bold.ttf'),
    Manrope_800ExtraBold: require('@expo-google-fonts/manrope/800ExtraBold/Manrope_800ExtraBold.ttf'),
  });
  if (!loaded && !error)
    return (
      <View
        style={{
          flex: 1,
          backgroundColor: c.background,
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <Text style={{ color: c.primaryDark }}>Loading Castly…</Text>
      </View>
    );
  if (configuration.mode === 'invalid')
    return (
      <SafeAreaProvider>
        <View
          style={{ flex: 1, padding: 24, justifyContent: 'center', backgroundColor: c.background }}
        >
          <Text accessibilityRole="alert" style={{ color: c.error }}>
            {configuration.error}
          </Text>
        </View>
      </SafeAreaProvider>
    );
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <CatalogProvider>
          <DemoProvider>
            <StatusBar style="dark" />
            <View style={{ flex: 1, backgroundColor: c.lavender }}>
              <View
                style={{
                  flex: 1,
                  width: '100%',
                  maxWidth: 600,
                  alignSelf: 'center',
                  backgroundColor: c.background,
                }}
              >
                <Stack
                  screenOptions={{
                    headerShown: false,
                    contentStyle: { backgroundColor: c.background },
                    animation: 'slide_from_right',
                  }}
                />
              </View>
            </View>
          </DemoProvider>
        </CatalogProvider>
      </AuthProvider>
    </SafeAreaProvider>
  );
}
