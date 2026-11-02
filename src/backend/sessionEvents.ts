// Only subject IDs cross this boundary. Access/refresh tokens never enter notices or logs.
const listeners = new Set<(subject: string | null) => void>();
export function sessionExpired(subject: string | null) {
  for (const listener of listeners) listener(subject);
}
export function onSessionExpired(listener: (subject: string | null) => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
