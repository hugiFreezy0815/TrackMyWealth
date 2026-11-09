import { useState } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';

import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';

/** Preserve the caller's form values; reload only the resource behind the conflict dialog. */
export function useConflict() {
  const client = useQueryClient();
  const [conflicted, setConflicted] = useState(false);
  async function handle(error: unknown, key: QueryKey): Promise<boolean> {
    if (!(error instanceof ApiError) || problemReaction(error).sentCode !== 'VERSION_CONFLICT') {
      return false;
    }
    setConflicted(true);
    await client.invalidateQueries({ queryKey: key });
    return true;
  }
  return { conflicted, dismiss: () => setConflicted(false), handle };
}

/** Use the cached resource's server version, never a locally incremented counter. */
export function ifMatch(resource: { version: number }): number {
  if (!Number.isSafeInteger(resource.version) || resource.version < 0) {
    throw new TypeError('Expected a server resource version');
  }
  return resource.version;
}
