import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';

import { typedApi } from '@/api/typed-api';

import {
  useAccountArchiveAction,
  useAccounts,
  useAccountTransactions,
  useChangeTransactionCategory,
  useUncategorizedCount,
} from './resources';

jest.mock('@/api/typed-api', () => ({
  typedApi: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

const api = jest.mocked(typedApi);

function wrapper() {
  // gcTime Infinity: no GC timers keep a single-file Jest run alive.
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const page = (totalElements: number) => ({
  content: [],
  page: { number: 0, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) },
});

beforeEach(() => {
  api.get.mockReset();
  api.post.mockReset();
  api.put.mockReset();
});

describe('S3 account detail API calls (#494)', () => {
  it('never fetches an unscoped account list while the portfolio is unknown', async () => {
    api.get.mockResolvedValue([] as never);
    const view = await renderHook(
      ({ portfolioId }: { portfolioId?: string }) =>
        useAccounts(portfolioId, { enabled: !!portfolioId }),
      { wrapper: wrapper(), initialProps: { portfolioId: undefined as string | undefined } },
    );
    expect(api.get).not.toHaveBeenCalled();
    await view.rerender({ portfolioId: 'p1' });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/api/v1/accounts', {
        query: { portfolioId: 'p1' },
      }),
    );
  });

  it('asks for 50 rows per page with the chosen sort and filter', async () => {
    api.get.mockResolvedValue(page(0) as never);
    const view = await renderHook(() => useAccountTransactions('acc', true, 'amount,asc'), {
      wrapper: wrapper(),
    });
    await waitFor(() => expect(view.result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/transactions', {
      params: { accountId: 'acc' },
      query: { page: 0, size: 50, uncategorized: true, sort: ['amount,asc'] },
    });
  });

  it('holds the list back while the account has no ledger', async () => {
    const view = await renderHook(
      () => useAccountTransactions('acc', false, 'bookingDate,desc', false),
      { wrapper: wrapper() },
    );
    expect(view.result.current.fetchStatus).toBe('idle');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('reads the uncategorised count from page.totalElements of a size-1 page', async () => {
    api.get.mockResolvedValue(page(3) as never);
    const view = await renderHook(() => useUncategorizedCount('acc'), { wrapper: wrapper() });
    await waitFor(() => expect(view.result.current.data).toBe(3));
    expect(api.get).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/transactions', {
      params: { accountId: 'acc' },
      query: { uncategorized: true, page: 0, size: 1 },
    });
  });

  it.each([
    [false, '/api/v1/accounts/{accountId}/archive'],
    [true, '/api/v1/accounts/{accountId}/restore'],
  ] as const)('sends If-Match with the read version (restore=%s)', async (restore, path) => {
    api.post.mockResolvedValue({} as never);
    const view = await renderHook(() => useAccountArchiveAction('acc'), { wrapper: wrapper() });
    await act(() => view.result.current.mutateAsync({ version: 7, restore }));
    expect(api.post).toHaveBeenCalledWith(path, { params: { accountId: 'acc' }, version: 7 });
  });

  it('sends the category with the transaction version as If-Match', async () => {
    api.put.mockResolvedValue({} as never);
    const view = await renderHook(() => useChangeTransactionCategory('acc'), {
      wrapper: wrapper(),
    });
    await act(() =>
      view.result.current.mutateAsync({ transactionId: 'tx', categoryId: 'food', version: 4 }),
    );
    expect(api.put).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}/category',
      {
        params: { accountId: 'acc', transactionId: 'tx' },
        version: 4,
        body: { categoryId: 'food' },
      },
    );
  });

  it('refuses a version that did not come from the server', async () => {
    const view = await renderHook(() => useAccountArchiveAction('acc'), { wrapper: wrapper() });
    await expect(
      act(() => view.result.current.mutateAsync({ version: -1, restore: false })),
    ).rejects.toThrow('Expected a server resource version');
    expect(api.post).not.toHaveBeenCalled();
  });
});
