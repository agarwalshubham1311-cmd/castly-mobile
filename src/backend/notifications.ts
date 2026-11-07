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

/** Reload the visible cursor window so a live refresh does not discard loaded history. */
export async function loadNotificationWindow(
  list: (before?: string) => Promise<NotificationRow[]>,
  pages: number,
) {
  let rows: NotificationRow[] = [],
    before: string | undefined,
    more = false;
  const seen = new Set<string>();
  for (let index = 0; index < pages; index++) {
    const page = await list(before);
    rows = mergeNotifications(rows, page);
    more = page.length === 50;
    if (!more) break;
    const cursor = page[page.length - 1].id;
    if (seen.has(cursor)) throw new Error('Notification history did not advance. Please retry.');
    seen.add(cursor);
    before = cursor;
  }
  return { rows, more };
}
