import React, { useState } from 'react';
import { ActivityIndicator, Image, ImageSourcePropType, StyleSheet, Text, View, ViewStyle, StyleProp } from 'react-native';

type Props = { source?: ImageSourcePropType; label: string; style?: StyleProp<ViewStyle>; children?: React.ReactNode };
/** Static require() sources are bundled by Metro on native and web. */
export function TalentImage({ source, label, style, children }: Props) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  return <View style={[styles.frame, style]}>
    {source && !failed && <Image source={source} accessibilityLabel={label} resizeMode="cover" style={[StyleSheet.absoluteFill, { width: '100%', height: '100%' }]} onLoadStart={() => setLoaded(false)} onLoad={() => setLoaded(true)} onError={() => setFailed(true)} />}
    {source && !loaded && !failed && <View style={styles.state} accessibilityLabel="Loading portrait"><ActivityIndicator color="#bda1f5" /></View>}
    {(!source || failed) && <View style={styles.state} accessibilityRole="image" accessibilityLabel={`${label}. Image unavailable`}><Text style={styles.icon}>◇</Text><Text style={styles.caption}>Portrait unavailable</Text></View>}
    {children}
  </View>;
}
const styles = StyleSheet.create({
  frame: { height: 240, backgroundColor: '#2d2044', borderRadius: 16, overflow: 'hidden' },
  state: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, alignItems: 'center', justifyContent: 'center', backgroundColor: '#2d2044', gap: 10 },
  icon: { color: '#bda1f5', fontSize: 36 },
  caption: { color: '#d0c4df', fontSize: 12 },
});
