import type { NotificationRow } from './types';
export const notificationTitles: Record<NotificationRow['kind'], string> = {
  booking_requested: 'New booking request',
  booking_accepted: 'Booking accepted',
  booking_declined: 'Booking rejected',
  booking_cancelled: 'Booking cancelled',
  message_received: 'New message',
};
export function notificationRoute(
  row: NotificationRow,
): `/chat/${string}` | `/booking-detail/${string}` | null {
  if (row.kind === 'message_received' && row.conversation_id) return `/chat/${row.conversation_id}`;
  if (row.booking_id) return `/booking-detail/${row.booking_id}`;
  return null;
}
export function mergeNotifications(previous: NotificationRow[], page: NotificationRow[]) {
  const fraction = (value: string) =>
    (value.match(/\.(\d+)/)?.[1] || '').padEnd(6, '0').slice(3, 6);
  return [...new Map([...previous, ...page].map((row) => [row.id, row])).values()].sort(
    (a, b) =>
      Date.parse(b.created_at) - Date.parse(a.created_at) ||
      fraction(b.created_at).localeCompare(fraction(a.created_at)) ||
      (b.id < a.id ? -1 : b.id > a.id ? 1 : 0),
  );
}
