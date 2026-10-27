import type { ConfigContext, ExpoConfig } from 'expo/config';
import { checkClientEnvironment } from './scripts/check-client-env.cjs';
export default function configuration({ config }: ConfigContext): ExpoConfig {
  checkClientEnvironment();
  if (
    process.env.NODE_ENV === 'production' &&
    process.env.EXPO_PUBLIC_BUSINESS_API_MODE === 'supabase'
  )
    throw new Error('Supabase business fallback is development-only; export with spring mode.');
  return {
    ...config,
    name: config.name || 'Castly',
    slug: config.slug || 'castly-mobile',
    plugins: [
      ...(config.plugins || []),
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
