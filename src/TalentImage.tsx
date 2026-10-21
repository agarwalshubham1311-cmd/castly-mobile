import React, { useState } from 'react';
import { c, f } from './theme';
import {
  ActivityIndicator,
  Image,
  ImageSourcePropType,
  StyleSheet,
  Text,
  View,
  ViewStyle,
  StyleProp,
} from 'react-native';

type Props = {
  source?: ImageSourcePropType;
  label: string;
  style?: StyleProp<ViewStyle>;
  children?: React.ReactNode;
  fit?: 'cover' | 'contain';
};
/** Static require() sources are bundled by Metro on native and web. */
export function TalentImage(props: Props) {
  return <ImageFrame key={JSON.stringify(props.source)} {...props} />;
}
function ImageFrame({ source, label, style, children, fit = 'cover' }: Props) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  return (
    <View style={[styles.frame, style]}>
      {source && !failed && (
        <Image
          source={source}
          accessibilityLabel={label}
          resizeMode={fit}
          style={[StyleSheet.absoluteFill, { width: '100%', height: '100%' }]}
          onLoadStart={() => setLoaded(false)}
          onLoad={() => setLoaded(true)}
          onError={() => setFailed(true)}
        />
      )}
      {source && !loaded && !failed && (
        <View style={styles.state} accessibilityLabel="Loading portrait">
          <ActivityIndicator color={c.primary} />
        </View>
      )}
      {(!source || failed) && (
        <View
          style={styles.state}
          accessibilityRole="image"
          accessibilityLabel={`${label}. Image unavailable`}
        >
          <Text style={styles.icon}>◇</Text>
          <Text style={styles.caption}>Portrait unavailable</Text>
        </View>
      )}
      {children}
    </View>
  );
}
const styles = StyleSheet.create({
  frame: {
    width: '100%',
    aspectRatio: 3 / 4,
    backgroundColor: c.lavender,
    borderRadius: 16,
    overflow: 'hidden',
  },
  state: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: c.lavender,
    gap: 10,
  },
  icon: { color: c.primary, fontSize: 36 },
  caption: { color: c.secondary, fontSize: 12, fontFamily: f.medium },
});
