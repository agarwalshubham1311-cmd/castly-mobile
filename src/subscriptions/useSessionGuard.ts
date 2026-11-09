import { useLayoutEffect, useRef } from 'react';
/** Discards prior-session and unmounted async completions, including A → B → A switches. */
export function useSessionGuard(actor: string | undefined) {
  const state = useRef({ generation: 0, mounted: true });
  useLayoutEffect(() => {
    const scope = state.current;
    scope.mounted = true;
    scope.generation++;
    return () => {
      scope.mounted = false;
      scope.generation++;
    };
  }, [actor]);
  return () => {
    const generation = state.current.generation;
    return () => state.current.mounted && state.current.generation === generation;
  };
}
