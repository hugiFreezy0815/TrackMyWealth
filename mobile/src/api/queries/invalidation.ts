import type { InfiniteData, Query, QueryClient } from '@tanstack/react-query';

import { queryRoots } from './keys';

/** Everything a commit, rollback, account or transaction change can move. */
const FINANCIAL_ROOTS: ReadonlySet<unknown> = new Set([
  queryRoots.netWorth,
  queryRoots.portfolios,
  queryRoots.accounts,
  queryRoots.cashFlow,
]);

const isFinancial = (query: Query) => FINANCIAL_ROOTS.has(query.queryKey[0]);

function isPaged(query: Query): boolean {
  const data = query.state.data as Partial<InfiniteData<unknown>> | undefined;
  return Array.isArray(data?.pages) && Array.isArray(data?.pageParams);
}

/**
 * Marks all financial data stale and refetches what is on screen. A paged list keeps only its
 * first page before the refetch: TanStack refetches every loaded page of an invalidated infinite
 * query, but a change can shift rows across pages, so the list restarts at page 0. The first
 * page stays rendered meanwhile (and while offline, when the refetch is paused).
 */
export async function invalidateFinancialData(client: QueryClient): Promise<void> {
  for (const query of client.getQueryCache().findAll({ predicate: isFinancial })) {
    if (!isPaged(query)) continue;
    client.setQueryData<InfiniteData<unknown>>(query.queryKey, (data) =>
      data && data.pages.length > 1
        ? { pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) }
        : data,
    );
  }
  await client.invalidateQueries({ predicate: isFinancial });
}
