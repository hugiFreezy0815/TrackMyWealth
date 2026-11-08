import type { QueryClient } from '@tanstack/react-query';
import { queryKeys } from './keys';

/** Invalidation refetches active infinite queries from page 0 (not stale page cursors). */
export async function invalidateFinancialData(
  client: QueryClient, accountId?: string, batchId?: string,
): Promise<void> {
  await Promise.all([
    client.invalidateQueries({ queryKey: ['net-worth'] }),
    client.invalidateQueries({ queryKey: queryKeys.portfolios }),
    client.invalidateQueries({ queryKey: ['accounts'] }),
    client.invalidateQueries({ queryKey: ['cash-flow'] }),
    ...(accountId ? [
      client.invalidateQueries({ queryKey: queryKeys.account(accountId) }),
      client.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] }),
    ] : []),
    ...(accountId && batchId ? [
      client.invalidateQueries({ queryKey: queryKeys.importBatch(accountId, batchId) }),
    ] : []),
  ]);
}
