export class BackendError extends Error {
  readonly kind: 'offline' | 'request';
  constructor(kind: 'offline' | 'request', message: string) {
    super(message);
    this.name = 'BackendError';
    this.kind = kind;
  }
}
export function readableError(error: unknown): string {
  const message =
    error instanceof Error
      ? error.message
      : typeof error === 'object' && error && 'message' in error
        ? String(error.message)
        : 'Request failed. Please try again.';
  if (/fetch|network|offline|abort|timeout|timed out|connection/i.test(message))
    return 'Cannot reach Castly. Check your connection and retry. Your changes have not been confirmed.';
  if (/invalid login credentials/i.test(message)) return 'Email or password is incorrect.';
  return message;
}
export function requireData<T>(result: { data: T; error: unknown }): T {
  if (result.error)
    throw new BackendError(
      /fetch|network|offline|abort|timeout|connection/i.test(readableError(result.error))
        ? 'offline'
        : 'request',
      readableError(result.error),
    );
  return result.data;
}
