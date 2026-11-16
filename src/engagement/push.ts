import { Platform } from 'react-native';
import Constants from 'expo-constants';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { services } from '../backend';
import { uuidPattern } from './types';
const key = 'castly.push.installation.v1';
export async function installationKey() { const previous = await AsyncStorage.getItem(key); if (previous && uuidPattern.test(previous)) return previous; const { randomUUID } = await import('expo-crypto'); const next = randomUUID(); await AsyncStorage.setItem(key, next); return next; }
export function pushBuildEnabled() { return process.env.EXPO_PUBLIC_PUSH_ENABLED === 'true' && Platform.OS !== 'web'; }
export async function registerPush(actor: string, requestPermission: boolean) {
  if (!pushBuildEnabled() || !services) throw new Error('Push requires an approved native development build and enabled push configuration.');
  const config = await services.engagement.configuration(); if (!config.push) throw new Error('Push registration is not enabled.');
  if (!requestPermission) { const preferences = await services.engagement.preferences(actor); if (!preferences.push_bookings && !preferences.push_messages) return; }
  const Notifications = await import('expo-notifications');
  // Android channel creation precedes the Android 13 permission prompt.
  if (Platform.OS === 'android') await Notifications.setNotificationChannelAsync('castly-updates', { name: 'Castly updates', importance: Notifications.AndroidImportance.DEFAULT, lockscreenVisibility: Notifications.AndroidNotificationVisibility.PRIVATE });
  let permission = await Notifications.getPermissionsAsync();
  if (!permission.granted && requestPermission) permission = await Notifications.requestPermissionsAsync();
  if (!permission.granted) throw new Error('Notifications are not permitted. You can enable them in device settings.');
  const projectId = Constants.easConfig?.projectId || Constants.expoConfig?.extra?.eas?.projectId;
  if (!projectId || !uuidPattern.test(projectId)) throw new Error('An approved EAS project ID and native build are required.');
  const token = await Notifications.getExpoPushTokenAsync({ projectId });
  await services.engagement.register(actor, await installationKey(), token.data, Platform.OS);
}
export async function unregisterPush(actor: string) { if (!services || !pushBuildEnabled()) return; const device = await AsyncStorage.getItem(key); if (device && uuidPattern.test(device)) await services.engagement.unregister(actor, device); }
