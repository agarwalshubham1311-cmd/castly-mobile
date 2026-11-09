import Constants from 'expo-constants';
import type { NativeBilling } from './billing';
export async function loadNativeBilling(): Promise<NativeBilling | null> {
  if (
    Constants.executionEnvironment === 'storeClient' ||
    process.env.EXPO_PUBLIC_GOOGLE_PLAY_BILLING_ENABLED !== 'true'
  )
    return null;
  try {
    return await import('react-native-iap');
  } catch {
    return null;
  }
}
