import { useQueries } from '@tanstack/react-query';

import { transactionQueryOptions } from '@/api/queries/resources';

import type { Transaction } from './model';

/** Use loaded originals first. Read only the referenced originals missing from the list. */
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
    queries: originalIds.map((id) => transactionQueryOptions(accountId, id)),
  });
  originals.forEach(({ data, isError }, index) => {
    const id = originalIds[index];
    if (
      !isError &&
      data?.id === id &&
      data.accountId === accountId &&
      typeof data.bookingDate === 'string'
    ) {
      dates.set(id, data.bookingDate);
    }
  });

  return dates;
}
