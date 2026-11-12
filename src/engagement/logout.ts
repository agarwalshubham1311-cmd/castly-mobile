type Cleanup = (actor: string) => Promise<void>;
const callbacks = new Set<Cleanup>();
export function beforeLogout(callback: Cleanup) { callbacks.add(callback); return () => { callbacks.delete(callback); }; }
export async function cleanupBeforeLogout(actor: string) {
  await Promise.all([...callbacks].map(async (callback) => { let timer: ReturnType<typeof setTimeout> | undefined; try { await Promise.race([callback(actor), new Promise<void>((resolve) => { timer = setTimeout(resolve, 4000); })]); } catch { /* Offline cleanup is best effort. Payloads remain generic and taps require authorization. */ } finally { if (timer) clearTimeout(timer); } }));
}
