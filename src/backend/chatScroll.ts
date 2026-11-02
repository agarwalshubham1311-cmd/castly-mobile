export function isNearBottom(offset: number, viewport: number, height: number) {
  return height - offset - viewport <= 80;
}
export function chatScrollTarget(
  previousHeight: number,
  height: number,
  offset: number,
  prepending: boolean,
  nearBottom: boolean,
  forceBottom: boolean,
) {
  if (prepending)
    return { kind: 'offset' as const, y: Math.max(0, offset + height - previousHeight) };
  if (forceBottom || nearBottom) return { kind: 'end' as const };
  return { kind: 'stay' as const };
}
