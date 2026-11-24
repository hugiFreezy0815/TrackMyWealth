import {
  queryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';

import { NetworkError, REQUEST_TIMEOUT_MS } from '@/api/client';
import { readAllPages } from '@/api/pages';
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
    queryFn: ({ signal }) =>
      readAllPages((page) => typedApi.get('/api/v1/portfolios', { query: { page }, signal })),
  });
export const portfolioViewQuery = (portfolioId: string, enabled = true) =>
  queryOptions({
    queryKey: queryKeys.portfolioView(portfolioId),
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/portfolios/{portfolioId}/view', { params: { portfolioId }, signal }),
    enabled: enabled && !!portfolioId,
  });

export const usePortfolioView = (portfolioId: string, { enabled = true }: QueryGate = {}) =>
  useQuery(portfolioViewQuery(portfolioId, enabled));
/** `enabled: false` holds the request back, e.g. until the scope currency is known (S1). */
export type QueryGate = { enabled?: boolean };

export const useNetWorth = (currency?: string, { enabled = true }: QueryGate = {}) =>
  useQuery({
    queryKey: queryKeys.netWorth(currency),
    queryFn: () => typedApi.get('/api/v1/net-worth', { query: currency ? { currency } : {} }),
    enabled,
  });
type HistoryPeriod = components['schemas']['ValueHistoryResponse']['period'];

export const useNetWorthHistory = (
  currency: string | undefined,
  period: HistoryPeriod,
  enabled: boolean,
) =>
  useQuery<components['schemas']['ValueHistoryResponse']>({
    queryKey: queryKeys.netWorthHistory(currency, period),
    // A period change may reuse the previous series offline, within the same currency only.
    placeholderData: (previous, query) => (query?.queryKey[1] === currency ? previous : undefined),
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/net-worth/history', { query: { currency, period }, signal }),
    enabled: enabled && !!currency,
  });

export const usePortfolioHistory = (portfolioId: string, period: HistoryPeriod, enabled: boolean) =>
  useQuery<components['schemas']['ValueHistoryResponse']>({
    queryKey: queryKeys.portfolioHistory(portfolioId, period),
    placeholderData: (previous, query) =>
      query?.queryKey[1] === portfolioId ? previous : undefined,
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/portfolios/{portfolioId}/history', {
        params: { portfolioId },
        query: { period },
        signal,
      }),
    enabled: enabled && !!portfolioId,
  });

export const useAccounts = (portfolioId?: string, { enabled = true }: QueryGate = {}) =>
  useQuery({
    queryKey: queryKeys.accounts({ portfolioId }),
    queryFn: ({ signal }) =>
      readAllPages((page) =>
        typedApi.get('/api/v1/accounts', { query: { page, portfolioId }, signal }),
      ),
    enabled,
  });
export const useAccount = (accountId: string) =>
  useQuery({
    queryKey: queryKeys.account(accountId),
    queryFn: () => typedApi.get('/api/v1/accounts/{accountId}', { params: { accountId } }),
    enabled: !!accountId,
  });
export const useAccountBalance = (accountId: string, currency?: string) =>
  useQuery({
    queryKey: [...queryKeys.account(accountId), 'balance', currency],
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/accounts/{accountId}/balance', {
        params: { accountId },
        query: { currency },
        signal,
      }),
    enabled: !!accountId && !!currency,
  });

export const useRecentAccountActivity = (accountId: string, enabled: boolean) =>
  useQuery({
    queryKey: queryKeys.accountTransactions(accountId, { recent: true }),
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/accounts/{accountId}/transactions', {
        params: { accountId },
        query: { size: 10, sort: ['bookingDate,desc'] },
        signal,
      }),
    enabled: !!accountId && enabled,
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
    queryFn: ({ signal }) =>
      readAllPages((page) =>
        typedApi.get('/api/v1/accounts', { query: { page, portfolioId, archived: true }, signal }),
      ),
    enabled: !!portfolioId && enabled,
  });

// Share the four-request limit and the cache across screens that read depot positions.
const POSITION_REQUEST_LIMIT = 4;
// A slot is held at most this long, so a request that never settles cannot block every later
// positions query in the app. One exchange can use three client deadlines - the request, a session
// renewal after its 401 and the resend - and reading the response body has none of its own, so
// the slot allows one more on top: it only ends a request the client's own deadlines missed.
export const POSITION_SLOT_TIMEOUT_MS = 4 * REQUEST_TIMEOUT_MS;
let positionRequestQueue: (() => void)[] = [];
let activePositionRequests = 0;

/** Test hook: forget queued and active positions requests left over by an earlier test. */
export function resetPositionRequests() {
  positionRequestQueue = [];
  activePositionRequests = 0;
}

function cancelled(signal: AbortSignal) {
  return signal.reason ?? new Error('Positions request cancelled');
}

async function waitForPositionSlot(signal: AbortSignal) {
  if (signal.aborted) throw cancelled(signal);
  if (activePositionRequests < POSITION_REQUEST_LIMIT) {
    activePositionRequests++;
    return;
  }
  await new Promise<void>((resolve, reject) => {
    const resume = () => {
      signal.removeEventListener('abort', cancel);
      resolve();
    };
    const cancel = () => {
      const index = positionRequestQueue.indexOf(resume);
      if (index >= 0) positionRequestQueue.splice(index, 1);
      reject(cancelled(signal));
    };
    positionRequestQueue.push(resume);
    signal.addEventListener('abort', cancel, { once: true });
  });
}

function releasePositionSlot() {
  const next = positionRequestQueue.shift();
  if (next) next();
  else activePositionRequests = Math.max(0, activePositionRequests - 1);
}

async function readAccountPositions(accountId: string, signal: AbortSignal) {
  await waitForPositionSlot(signal);
  const controller = new AbortController();
  const forwardAbort = () => controller.abort(signal.reason);
  signal.addEventListener('abort', forwardAbort, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    if (signal.aborted) throw cancelled(signal);
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        // A NetworkError like the client's own timeout, so React Query retries it (shouldRetry).
        const timeout = new NetworkError('Positions request timed out');
        controller.abort(timeout);
        reject(timeout);
      }, POSITION_SLOT_TIMEOUT_MS);
    });
    return await Promise.race([
      typedApi.get('/api/v1/accounts/{accountId}/positions', {
        params: { accountId },
        signal: controller.signal,
      }),
      deadline,
    ]);
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', forwardAbort);
    releasePositionSlot();
  }
}

export const accountPositionsQuery = (accountId: string, enabled = true) =>
  queryOptions({
    queryKey: queryKeys.accountPositions(accountId),
    queryFn: ({ signal }) => readAccountPositions(accountId, signal),
    enabled: !!accountId && enabled,
  });

export const useAccountPositions = (accountId: string, enabled = true) =>
  useQuery(accountPositionsQuery(accountId, enabled));

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

type AccountSummary = components['schemas']['AccountSummaryResponse'];

export function useUpdateAccountOpeningDate(accountId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ account, openedAt }: { account: AccountSummary; openedAt: string | null }) =>
      typedApi.put('/api/v1/accounts/{accountId}', {
        params: { accountId },
        version: ifMatch(account),
        body: {
          name: account.name,
          accountType: account.accountType,
          nativeCurrency: account.nativeCurrency,
          jurisdiction: account.jurisdiction ?? undefined,
          closedAt: account.closedAt ?? undefined,
          openedAt: openedAt ?? undefined,
        },
      }),
    onSuccess: async (updated) => {
      client.setQueryData(queryKeys.account(accountId), updated);
      await invalidateFinancialData(client);
    },
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

type TransactionsPage = components['schemas']['PageResponseTransactionResponse'];

export function readTransaction(accountId: string, transactionId: string, signal: AbortSignal) {
  return typedApi.get('/api/v1/accounts/{accountId}/transactions/{transactionId}', {
    params: { accountId, transactionId },
    signal,
  });
}

/** Display an S3 row instantly while fetching the authoritative single-row response. */
export function useTransaction(accountId: string, transactionId: string) {
  const client = useQueryClient();
  return useQuery<components['schemas']['TransactionResponse']>({
    queryKey: queryKeys.transaction(accountId, transactionId),
    queryFn: ({ signal }) => readTransaction(accountId, transactionId, signal),
    enabled: !!accountId && !!transactionId,
    refetchOnMount: 'always',
    // Placeholder data is never authoritative and must not enable editing.
    placeholderData: () => {
      const cached = client.getQueriesData<InfiniteData<TransactionsPage>>({
        queryKey: queryKeys.accountTransactionScope(accountId),
      });
      for (const [, data] of cached) {
        for (const page of data?.pages ?? []) {
          const match = page.content.find((row) => row.id === transactionId);
          if (match) return match;
        }
      }
      return undefined;
    },
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
    queryFn: ({ signal }) =>
      readAllPages((page) =>
        typedApi.get('/api/v1/categories', { query: { page, includeInactive }, signal }),
      ),
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
