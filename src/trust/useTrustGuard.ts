import { useLayoutEffect, useRef } from 'react';
export function useTrustGuard(actor: string | undefined) {
  const state = useRef({ generation: 0, mounted: true });
  useLayoutEffect(() => {
    const scope = state.current;
    scope.generation++;
    scope.mounted = true;
    return () => {
      scope.generation++;
      scope.mounted = false;
    };
  }, [actor]);
  return () => {
    const generation = state.current.generation;
    return () => state.current.mounted && generation === state.current.generation;
  };
}
