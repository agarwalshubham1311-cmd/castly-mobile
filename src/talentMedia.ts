import type { ImageSourcePropType } from 'react-native';

// Static imports let Metro include every photograph in Android, iOS and web exports.
export const talentMedia: Record<string, ImageSourcePropType> = {
  'portrait-01': require('../assets/talent/portrait-01.jpg'),
  'portrait-02': require('../assets/talent/portrait-02.jpg'),
  'portrait-03': require('../assets/talent/portrait-03.jpg'),
  'portrait-04': require('../assets/talent/portrait-04.jpg'),
  'portrait-05': require('../assets/talent/portrait-05.jpg'),
  'portrait-06': require('../assets/talent/portrait-06.jpg'),
  'portrait-07': require('../assets/talent/portrait-07.jpg'),
  'portrait-08': require('../assets/talent/portrait-08.jpg'),
  'portrait-09': require('../assets/talent/portrait-09.jpg'),
  'portrait-10': require('../assets/talent/portrait-10.jpg'),
  'portrait-11': require('../assets/talent/portrait-11.jpg'),
};

export function imageSource(key: string, uri?: string): ImageSourcePropType | undefined {
  return uri ? { uri } : talentMedia[key];
}
