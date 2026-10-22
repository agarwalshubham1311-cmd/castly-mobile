import React from 'react';
import { useCatalog } from '../backend/CatalogProvider';
import { EmptyState, LoadingSkeleton } from './ui';
export function BackendNotice() {
  const catalog = useCatalog();
  if (catalog.mock) return null;
  if (catalog.error)
    return (
      <EmptyState
        title="Talent unavailable"
        detail={catalog.error}
        action="Retry discovery"
        onPress={() => {
          void catalog.refresh();
        }}
      />
    );
  if (catalog.loading && !catalog.talents.length)
    return <LoadingSkeleton label="Loading published talent" />;
  if (!catalog.talents.length)
    return (
      <EmptyState
        title="Discoveries start here"
        detail="No talent profiles have been published yet."
      />
    );
  return null;
}
