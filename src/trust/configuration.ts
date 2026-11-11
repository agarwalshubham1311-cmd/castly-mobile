import { services } from '../backend';
import type { SafetyConfiguration } from './api';
let cached: { actor: string; expires: number; pending: Promise<SafetyConfiguration> } | undefined;
/** Coalesce repeated row controls without retaining another account's permissions. Writes always reauthorize on the server. */
export function safetyConfiguration(actor: string) {
  if (!cached || cached.actor !== actor || cached.expires < Date.now()) {
    cached = { actor, expires: Date.now() + 30000, pending: services!.trust.configuration() };
  }
  return cached.pending;
}
