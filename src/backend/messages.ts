import type { MessageRow } from './types.ts';
/** Preserve server ordering at PostgreSQL microsecond precision and deduplicate reconnect/POST results. */
export function mergeMessages(...pages: MessageRow[][]): MessageRow[] {
  const fraction = (value: string) =>
    (value.match(/\.(\d+)/)?.[1] || '').padEnd(6, '0').slice(3, 6);
  return [...new Map(pages.flat().map((row) => [row.id, row])).values()].sort(
    (a, b) =>
      Date.parse(a.created_at) - Date.parse(b.created_at) ||
      fraction(a.created_at).localeCompare(fraction(b.created_at)) ||
      (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}

/** A disconnected latest page must not be joined to stale older pages across an unobserved gap. */
export function messageWindow(history: MessageRow[], latest: MessageRow[], pageSize = 50) {
  const ids = new Set(history.map((row) => row.id));
  const reset =
    history.length > 0 && latest.length >= pageSize && !latest.some((row) => ids.has(row.id));
  return { rows: reset ? latest : mergeMessages(history, latest), reset };
}
