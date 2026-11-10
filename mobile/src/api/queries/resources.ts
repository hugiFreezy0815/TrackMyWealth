import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';

import type { components } from '@/api/schema';

import { typedApi } from '@/api/typed-api';
import { ifMatch } from './conflict';
import { invalidateFinancialData } from './invalidation';
import { queryKeys } from './keys';

export const useWorkspace = () =>
  useQuery({
    queryKey: queryKeys.workspace,
    queryFn: () => typedApi.get('/api/v1/workspace'),
  });
export const useCurrentUser = () =>
  useQuery({
    queryKey: queryKeys.me,
    queryFn: () => typedApi.get('/api/v1/users/me'),
  });
export const usePortfolios = () =>
  useQuery({
    queryKey: queryKeys.portfolios,
    queryFn: () => typedApi.get('/api/v1/portfolios'),
  });
export const usePortfolioView = (portfolioId: string) =>
  useQuery({
    queryKey: queryKeys.portfolioView(portfolioId),
    queryFn: () =>
      typedApi.get('/api/v1/portfolios/{portfolioId}/view', { params: { portfolioId } }),
    enabled: !!portfolioId,
  });
/** `enabled: false` holds the request back, e.g. until the scope currency is known (S1). */
export type QueryGate = { enabled?: boolean };

export const useNetWorth = (currency?: string, { enabled = true }: QueryGate = {}) =>
  useQuery({
    queryKey: queryKeys.netWorth(currency),
    queryFn: () => typedApi.get('/api/v1/net-worth', { query: currency ? { currency } : {} }),
    enabled,
  });
export const useAccounts = (portfolioId?: string) =>
  useQuery({
    queryKey: queryKeys.accounts({ portfolioId }),
    queryFn: () => typedApi.get('/api/v1/accounts', { query: portfolioId ? { portfolioId } : {} }),
  });
export const useAccount = (accountId: string) =>
  useQuery({
    queryKey: queryKeys.account(accountId),
    queryFn: () => typedApi.get('/api/v1/accounts/{accountId}', { params: { accountId } }),
    enabled: !!accountId,
  });
export type TransactionSort = 'bookingDate,desc' | 'bookingDate,asc' | 'amount,desc' | 'amount,asc';

/** Each sort/filter gets a separate cache. Changing either begins at page zero. */
export const useAccountTransactions = (
  accountId: string,
  uncategorized = false,
  sort: TransactionSort = 'bookingDate,desc',
  enabled = true,
) =>
  useInfiniteQuery({
    queryKey: queryKeys.accountTransactions(accountId, { uncategorized, sort }),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions', {
        params: { accountId },
        query: { page: pageParam, size: 50, uncategorized, sort: [sort] },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    enabled: !!accountId && enabled,
  });

/** Count is server-authoritative: never subtract a local count after a mutation. */
export const useUncategorizedCount = (accountId: string, enabled = true) =>
  useQuery({
    queryKey: queryKeys.uncategorizedCount(accountId),
    queryFn: async () => {
      const page = await typedApi.get('/api/v1/accounts/{accountId}/transactions', {
        params: { accountId },
        query: { uncategorized: true, page: 0, size: 1 },
      });
      return page.page.totalElements;
    },
    enabled: !!accountId && enabled,
  });

export const useArchivedAccounts = (portfolioId: string, enabled = true) =>
  useQuery({
    queryKey: queryKeys.accounts({ portfolioId, archived: true }),
    queryFn: () =>
      typedApi.get('/api/v1/accounts', {
        query: { portfolioId, archived: true },
      }),
    enabled: !!portfolioId && enabled,
  });

export const useAccountPositions = (accountId: string, enabled = true) =>
  useQuery({
    queryKey: queryKeys.accountPositions(accountId),
    queryFn: () =>
      typedApi.get('/api/v1/accounts/{accountId}/positions', {
        params: { accountId },
      }),
    enabled: !!accountId && enabled,
  });

/** Every write sends the exact version read from the server. */
export function useAccountArchiveAction(accountId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ version, restore }: { version: number; restore: boolean }) =>
      restore
        ? typedApi.post('/api/v1/accounts/{accountId}/restore', {
            params: { accountId },
            version: ifMatch({ version }),
          })
        : typedApi.post('/api/v1/accounts/{accountId}/archive', {
            params: { accountId },
            version: ifMatch({ version }),
          }),
    onSuccess: () => invalidateFinancialData(client),
  });
}

export function useChangeTransactionCategory(accountId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      transactionId,
      categoryId,
      version,
    }: {
      transactionId: string;
      categoryId: string;
      version: number;
    }) =>
      typedApi.put('/api/v1/accounts/{accountId}/transactions/{transactionId}/category', {
        params: { accountId, transactionId },
        version: ifMatch({ version }),
        body: { categoryId },
      }),
    onSuccess: () => invalidateFinancialData(client),
  });
}

/** Display an S3 row instantly, but fetch the authoritative single-row response.
 * Placeholder data is never authoritative and must not enable editing.
 */
export function useTransaction(accountId: string, transactionId: string) {
  const client = useQueryClient();
  return useQuery({
    queryKey: queryKeys.transaction(accountId, transactionId),
    queryFn: () =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions/{transactionId}', {
        params: { accountId, transactionId },
      }),
    enabled: !!accountId && !!transactionId,
    refetchOnMount: 'always',
    placeholderData: () =>
      client
        .getQueriesData<InfiniteData<components['schemas']['PageResponseTransactionResponse']>>({
          queryKey: queryKeys.accountTransactionScope(accountId),
        })
        .flatMap(([, data]) =>
          Array.isArray(data?.pages) ? data.pages.flatMap((page) => page.content) : [],
        )
        .find((row) => row.id === transactionId),
  });
}
/** S4: history pages stay independent of the main transaction list. */
export const useTransactionHistory = (accountId: string, transactionId: string) =>
  useInfiniteQuery({
    queryKey: queryKeys.transactionHistory(accountId, transactionId),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions/{transactionId}/history', {
        params: { accountId, transactionId },
        query: { page: pageParam, size: 50, sort: ['occurredAt,desc'] },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    enabled: !!accountId && !!transactionId,
  });

export const useSecurity = (securityId: string) =>
  useQuery({
    queryKey: queryKeys.security(securityId),
    queryFn: () => typedApi.get('/api/v1/securities/{id}', { params: { id: securityId } }),
    enabled: !!securityId,
  });

/** Updating S4 invalidates S3 page zero, counts, totals and history. */
export function useTransactionDetailActions(accountId: string, transactionId: string) {
  const client = useQueryClient();
  const sync = async (updated?: components['schemas']['TransactionResponse']) => {
    if (updated) client.setQueryData(queryKeys.transaction(accountId, transactionId), updated);
    await invalidateFinancialData(client);
  };
  const setCategory = useMutation({
    mutationFn: ({ categoryId, version }: { categoryId: string; version: number }) =>
      typedApi.put('/api/v1/accounts/{accountId}/transactions/{transactionId}/category', {
        params: { accountId, transactionId },
        body: { categoryId },
        version: ifMatch({ version }),
      }),
    onSuccess: (updated) => sync(updated),
  });
  const resetCategory = useMutation({
    mutationFn: ({ version }: { version: number }) =>
      typedApi.delete('/api/v1/accounts/{accountId}/transactions/{transactionId}/category', {
        params: { accountId, transactionId },
        version: ifMatch({ version }),
      }),
    onSuccess: (updated) => sync(updated),
  });
  const remove = useMutation({
    mutationFn: ({ version, reason }: { version: number; reason?: string }) =>
      typedApi.delete('/api/v1/accounts/{accountId}/transactions/{transactionId}', {
        params: { accountId, transactionId },
        version: ifMatch({ version }),
        query: reason ? { reason } : {},
      }),
    onSuccess: () => sync(),
  });
  return { setCategory, resetCategory, remove };
}


export const useCategories = (includeInactive = false) =>
  useQuery({
    queryKey: queryKeys.categories(includeInactive),
    queryFn: () => typedApi.get('/api/v1/categories', { query: { includeInactive } }),
  });
export const useCashFlow = (month: string, currency?: string, { enabled = true }: QueryGate = {}) =>
  useQuery({
    queryKey: queryKeys.cashFlow(month, currency),
    queryFn: () =>
      typedApi.get('/api/v1/cash-flow', { query: { month, ...(currency ? { currency } : {}) } }),
    enabled: enabled && !!month,
  });
export const useImportBatch = (accountId: string, batchId: string) =>
  useQuery({
    queryKey: queryKeys.importBatch(accountId, batchId),
    queryFn: () =>
      typedApi.get('/api/v1/accounts/{accountId}/imports/{batchId}', {
        params: { accountId, batchId },
      }),
    enabled: !!accountId && !!batchId,
  });
export const useImportRows = (accountId: string, batchId: string, status?: string) =>
  useInfiniteQuery({
    queryKey: queryKeys.importRows(accountId, batchId, status),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/accounts/{accountId}/imports/{batchId}/rows', {
        params: { accountId, batchId },
        query: { page: pageParam, size: 50, ...(status ? { status } : {}) },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    enabled: !!accountId && !!batchId,
  });

/** Commit/rollback refresh all affected totals and paged lists. */
export function useCommitImport(accountId: string, batchId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (resource: { version: number }) =>
      typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/commit', {
        params: { accountId, batchId },
        version: ifMatch(resource),
      }),
    onSuccess: () => invalidateFinancialData(client),
  });
}
export function useRollbackImport(accountId: string, batchId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ resource, reason }: { resource: { version: number }; reason: string }) =>
      typedApi.post('/api/v1/accounts/{accountId}/imports/{batchId}/rollback', {
        params: { accountId, batchId },
        version: ifMatch(resource),
        body: { reason },
      }),
    onSuccess: () => invalidateFinancialData(client),
  });
}
