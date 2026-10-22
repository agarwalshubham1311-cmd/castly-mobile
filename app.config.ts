import type { ConfigContext, ExpoConfig } from 'expo/config';
import { checkClientEnvironment } from './scripts/check-client-env.cjs';
export default function configuration({ config }: ConfigContext): ExpoConfig {
  checkClientEnvironment();
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
