import type { ConfigContext, ExpoConfig } from 'expo/config';
import { checkClientEnvironment } from './scripts/check-client-env.cjs';
import { checkReleaseEnvironment } from './scripts/check-release-env.cjs';
export default function configuration({ config }: ConfigContext): ExpoConfig {
  checkClientEnvironment();
  if (process.env.CASTLY_RELEASE_CHECK === '1') checkReleaseEnvironment();
  const projectId = process.env.EXPO_EAS_PROJECT_ID?.trim();
  const androidPackage = process.env.CASTLY_ANDROID_PACKAGE?.trim() || 'com.castly.mobile';
  if (projectId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(projectId))
    throw new Error('EXPO_EAS_PROJECT_ID must be the actual EAS project UUID.');
  if (androidPackage && !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/i.test(androidPackage))
    throw new Error('CASTLY_ANDROID_PACKAGE must be the owned reverse-DNS Android package.');
  if (process.env.EAS_BUILD === 'true' && (!projectId || !androidPackage))
    throw new Error('EAS builds require EXPO_EAS_PROJECT_ID and CASTLY_ANDROID_PACKAGE.');
  if (
    process.env.NODE_ENV === 'production' &&
    process.env.EXPO_PUBLIC_BUSINESS_API_MODE === 'supabase'
  )
    throw new Error('Supabase business fallback is development-only; export with spring mode.');
  return {
    ...config,
    name: config.name || 'Castly',
    slug: config.slug || 'castly-mobile',
    ...(androidPackage ? { android: { ...config.android, package: androidPackage } } : {}),
    ...(projectId ? { extra: { ...config.extra, eas: { ...config.extra?.eas, projectId } } } : {}),
    plugins: [
      ...(config.plugins || []),
      ...(process.env.EXPO_PUBLIC_GOOGLE_PLAY_BILLING_ENABLED === 'true' ? ['react-native-iap'] : []),
      [
        'expo-image-picker',
        {
          photosPermission: 'Allow Castly to choose photographs for your talent portfolio.',
          cameraPermission: false,
          microphonePermission: false,
        },
      ],
    ],
  };
}
