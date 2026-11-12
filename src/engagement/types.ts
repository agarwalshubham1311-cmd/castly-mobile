export type EngagementConfiguration = { push: boolean; email: boolean; engagement: boolean; analytics: boolean; push_delivery: boolean; email_delivery: boolean };
export type Preferences = { timezone: string; quiet_start: number; quiet_end: number; push_bookings: boolean; push_messages: boolean; email_transactional: boolean; email_optional: boolean; reminders: boolean; saved_alerts: boolean; reengagement: boolean; analytics: boolean };
export const defaultPreferences: Preferences = { timezone: 'UTC', quiet_start: 22, quiet_end: 8, push_bookings: false, push_messages: false, email_transactional: false, email_optional: false, reminders: false, saved_alerts: false, reengagement: false, analytics: false };
export type SavedFilters = { category?: string; city?: string; region?: string; skills?: string[]; max_rate?: number | null; days_ahead?: number | null; window_days?: number | null; include_unknown: boolean };
export type SavedSearch = { id: string; label: string; filters: string | SavedFilters; alerts: boolean };
export const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function deliveryId(data: unknown): string | null { if (!data || typeof data !== 'object') return null; const value = (data as Record<string, unknown>).delivery_id; return typeof value === 'string' && uuidPattern.test(value) ? value : null; }
export function safeNotificationRoute(value: string): boolean { return value === '/notifications' || value === '/engagement' || /^\/(booking-detail|chat)\/[0-9a-f-]{36}$/i.test(value) && uuidPattern.test(value.split('/')[2]); }
export function preferenceInput(value: Preferences): Preferences { return Object.fromEntries(Object.keys(defaultPreferences).map((key) => [key, value[key as keyof Preferences]])) as Preferences; }
