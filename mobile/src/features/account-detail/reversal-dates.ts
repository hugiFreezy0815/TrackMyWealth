import { useQueries } from '@tanstack/react-query';

import { ApiError } from '@/api/client';
import { queryKeys } from '@/api/queries/keys';
import { readTransaction } from '@/api/queries/resources';

import { isAccessFailure } from './access';
import type { Transaction } from './model';

// A rollback can put 50 reversals on one page. Leave capacity for ledger and detail reads.
const MAX_ACTIVE_READS = 4;
let activeReads = 0;
const queuedReads = new Set<() => void>();

function queueDateRead(signal: AbortSignal, read: () => Promise<string | null>) {
  return new Promise<string | null>((resolve, reject) => {
    function cancel() {
      queuedReads.delete(start);
      reject(new Error('Reversal date read cancelled'));
    }
    function start() {
      queuedReads.delete(start);
      signal.removeEventListener('abort', cancel);
      activeReads += 1;
      void read()
        .then(resolve, reject)
        .finally(() => {
          activeReads -= 1;
          queuedReads.values().next().value?.();
        });
    }
    if (signal.aborted) {
      cancel();
    } else if (activeReads < MAX_ACTIVE_READS) {
      start();
    } else {
      queuedReads.add(start);
      signal.addEventListener('abort', cancel, { once: true });
    }
  });
}

async function fetchOriginalDate(accountId: string, id: string, signal: AbortSignal) {
  try {
    const row = await readTransaction(accountId, id, signal);
    return row?.id === id && row.accountId === accountId && typeof row.bookingDate === 'string'
      ? row.bookingDate
      : null;
  } catch (error) {
    // 410 Gone is final as well. Store the absence so a later transient failure cannot restore it.
    if (isAccessFailure(error) || (error instanceof ApiError && error.status === 410)) return null;
    throw error;
  }
}

/** Use loaded originals first and fetch missing dates independently of Transaction Detail. */
export function useReversalDates(accountId: string, rows: Transaction[], enabled: boolean) {
  const dates = new Map(rows.map((row) => [row.id, row.bookingDate]));
  const missing = new Set<string>();
  if (enabled) {
    for (const row of rows) {
      if (row.replacesTransactionId && !dates.has(row.replacesTransactionId)) {
        missing.add(row.replacesTransactionId);
      }
    }
  }

  const originalIds = [...missing];
  const originals = useQueries({
    queries: originalIds.map((id) => ({
      queryKey: queryKeys.reversalDate(accountId, id),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        queueDateRead(signal, () => fetchOriginalDate(accountId, id, signal)),
    })),
  });
  originals.forEach(({ data }, index) => {
    // TanStack Query retains the last successful value when a background request fails.
    if (data) dates.set(originalIds[index], data);
  });

  return dates;
}
