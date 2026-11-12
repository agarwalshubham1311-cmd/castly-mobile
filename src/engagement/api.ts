import type { DiscoveryCandidate } from '../discovery/types';
import type { createApiTransport } from '../backend/api';
import type { EngagementConfiguration, Preferences, SavedFilters, SavedSearch } from './types.ts';
import { preferenceInput } from './types.ts';
export function createEngagementServices(request: ReturnType<typeof createApiTransport>) {
  const scoped = (actor: string) => ({ expectedSubject: actor });
  return {
    configuration: () => request<EngagementConfiguration>('engagement/configuration'),
    preferences: (actor: string) => request<Preferences>('engagement/preferences', 'GET', undefined, scoped(actor)),
    update: (actor: string, value: Preferences) => request<Preferences>('engagement/preferences', 'PUT', preferenceInput(value), scoped(actor)),
    register: (actor: string, deviceKey: string, token: string, platform: string) => request('engagement/devices', 'POST', { device_key: deviceKey, token, platform }, scoped(actor)),
    unregister: (actor: string, deviceKey: string) => request<void>(`engagement/devices/${encodeURIComponent(deviceKey)}`, 'DELETE', undefined, scoped(actor)),
    verifyEmail: (actor: string) => request<{ verified: boolean }>('engagement/email/verify', 'POST', {}, scoped(actor)),
    saved: (actor: string) => request<SavedSearch[]>('engagement/saved-searches', 'GET', undefined, scoped(actor)),
    save: (actor: string, label: string, filters: SavedFilters, alerts: boolean, id?: string) => request<SavedSearch>(`engagement/saved-searches${id ? '/' + encodeURIComponent(id) : ''}`, id ? 'PUT' : 'POST', { label, filters, alerts }, scoped(actor)),
    results: (actor: string, id: string) => request<DiscoveryCandidate[]>(`engagement/saved-searches/${encodeURIComponent(id)}/results`, 'POST', {}, scoped(actor)),
    remove: (actor: string, id: string) => request<void>(`engagement/saved-searches/${encodeURIComponent(id)}`, 'DELETE', undefined, scoped(actor)),
    open: (actor: string, id: string) => request<{ route: string }>(`engagement/open/${encodeURIComponent(id)}`, 'POST', {}, scoped(actor)),
    export: (actor: string) => request<Record<string, unknown>>('engagement/export', 'GET', undefined, scoped(actor)),
    delete: (actor: string) => request<void>('engagement/data', 'DELETE', undefined, scoped(actor)),
    analytics: (actor: string) => request<Record<string, unknown>>('engagement/admin/analytics', 'GET', undefined, scoped(actor)),
  };
}
