import { useCallback, useEffect, useState } from 'react';
import { talents } from '../talent';
import type { Talent } from '../talent';
import { services, configuration } from './index';
import { mapTalent } from './catalog';
import { ApiError } from './api';
import { readableError } from './errors';
/** A shared profile URL must work without first visiting Discovery or loading its pages. */
export function usePublishedTalent(id: string) {
  const mock = configuration.mode === 'mock';
  const [result, setResult] = useState<{
    id: string;
    talent: Talent | null;
    error: string;
    mediaUnavailable: boolean;
  } | null>(null);
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => {
    setResult(null);
    setRevision((v) => v + 1);
  }, []);
  useEffect(() => {
    if (mock || !services) return;
    let active = true;
    Promise.all([services.talent.get(id), services.portfolio.list(id, true)])
      .then(async ([profile, assets]) =>
        mapTalent(profile, assets || [], services!.media.signedUrl),
      )
      .then(({ talent, mediaUnavailable }) => {
        if (active) setResult({ id, talent, error: '', mediaUnavailable });
      })
      .catch((error) => {
        if (active)
          setResult({
            id,
            talent: null,
            mediaUnavailable: false,
            error: error instanceof ApiError && error.status === 404 ? '' : readableError(error),
          });
      });
    return () => {
      active = false;
    };
  }, [id, mock, revision]);
  return {
    mock,
    retry,
    talent: mock ? talents.find((t) => t.id === id) : result?.id === id ? result.talent : null,
    loading: !mock && result?.id !== id,
    error: result?.id === id ? result.error : '',
    mediaUnavailable: result?.id === id && result.mediaUnavailable,
  };
}
