import { useState } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';

import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';

import { invalidateFromFirstPage } from './invalidation';

/** Preserve the caller's form values; reload only the resource behind the conflict dialog. */
export function useConflict() {
  const client = useQueryClient();
  const [conflicted, setConflicted] = useState(false);
  async function handle(error: unknown, key: QueryKey): Promise<boolean> {
    if (!(error instanceof ApiError) || problemReaction(error).sentCode !== 'VERSION_CONFLICT') {
      return false;
    }
    setConflicted(true);
    // A paged list behind the conflict restarts at page 0, like after any other write.
    await invalidateFromFirstPage(client, { queryKey: key });
    return true;
  }
  return { conflicted, dismiss: () => setConflicted(false), handle };
}

/**
 * A known VERSION_REQUIRED (428) allows one resource reload and one resubmission
 * with the latest server ETag. An unknown code or another failed retry is surfaced.
 */
export async function retryVersionRequired<T>(
  write: (version: number) => Promise<T>,
  version: number,
  reload: () => Promise<{ version: number }>,
): Promise<T> {
  try {
    return await write(ifMatch({ version }));
  } catch (error) {
    if (
      !(error instanceof ApiError) ||
      error.status !== 428 ||
      problemReaction(error).sentCode !== 'VERSION_REQUIRED'
    ) {
      throw error;
    }
    const latest = await reload();
    return write(ifMatch(latest));
  }
}

/** Use the cached resource's server version, never a locally incremented counter. */
export function ifMatch(resource: { version: number }): number {
  if (!Number.isSafeInteger(resource.version) || resource.version < 0) {
    throw new TypeError('Expected a server resource version');
  }
  return resource.version;
}
