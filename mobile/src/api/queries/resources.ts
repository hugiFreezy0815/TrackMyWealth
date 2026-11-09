import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

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
export const useNetWorth = (currency?: string) =>
  useQuery({
    queryKey: queryKeys.netWorth(currency),
    queryFn: () => typedApi.get('/api/v1/net-worth', { query: currency ? { currency } : {} }),
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
export const useAccountTransactions = (accountId: string, uncategorized = false) =>
  useInfiniteQuery({
    queryKey: queryKeys.accountTransactions(accountId, { uncategorized }),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions', {
        params: { accountId },
        query: { page: pageParam, size: 50, uncategorized },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    enabled: !!accountId,
  });
export const useTransaction = (accountId: string, transactionId: string) =>
  useQuery({
    queryKey: queryKeys.transaction(accountId, transactionId),
    queryFn: () =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions/{transactionId}', {
        params: { accountId, transactionId },
      }),
    enabled: !!accountId && !!transactionId,
  });
export const useCategories = (includeInactive = false) =>
  useQuery({
    queryKey: queryKeys.categories(includeInactive),
    queryFn: () => typedApi.get('/api/v1/categories', { query: { includeInactive } }),
  });
export const useCashFlow = (month: string, currency?: string) =>
  useQuery({
    queryKey: queryKeys.cashFlow(month, currency),
    queryFn: () =>
      typedApi.get('/api/v1/cash-flow', { query: { month, ...(currency ? { currency } : {}) } }),
    enabled: !!month,
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
