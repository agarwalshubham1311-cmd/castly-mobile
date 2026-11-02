/** The legacy factory is lazy: Spring mode cannot construct a direct database business service. */
export function composeServices<
  S extends { activity: object },
  P extends object,
  L extends { activity: object },
>(mode: 'spring' | 'supabase', spring: S, platform: P, legacy: () => L) {
  if (mode === 'spring') return { ...spring, ...platform };
  const fallback = legacy();
  const unavailable = Object.fromEntries(
    Object.keys(spring.activity).map((key) => [
      key,
      () => Promise.reject(new Error('This action requires Spring business mode.')),
    ]),
  ) as S['activity'];
  return {
    ...spring,
    ...fallback,
    ...platform,
    activity: { ...unavailable, ...fallback.activity },
  };
}
