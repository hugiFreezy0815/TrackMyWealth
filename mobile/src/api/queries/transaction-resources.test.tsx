import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';

import { typedApi } from '@/api/typed-api';
import type { components } from '@/api/schema';
import { queryKeys } from './keys';
import {
  useCategories,
  useTransaction,
  useTransactionDetailActions,
  useTransactionHistory,
} from './resources';

jest.mock('@/api/typed-api', () => ({
  typedApi: { get: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

const api = jest.mocked(typedApi);
type Transaction = components['schemas']['TransactionResponse'];

function setup() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

beforeEach(() => {
  api.get.mockReset();
  api.put.mockReset();
  api.delete.mockReset();
});

describe('S4 transaction queries', () => {
  it('shows the S3 list row immediately, then replaces it with the authoritative detail', async () => {
    const { client, wrapper } = setup();
    const row = {
      id: 'txn',
      accountId: 'acc',
      version: 2,
      merchantDescription: 'From list',
    } as Transaction;
    const latest = { ...row, version: 5, merchantDescription: 'From server' };
    client.setQueryData(
      queryKeys.accountTransactions('acc', {
        uncategorized: false,
        sort: 'bookingDate,desc',
      }),
      {
        pages: [{ content: [row], page: { number: 0, size: 50, totalElements: 1, totalPages: 1 } }],
        pageParams: [0],
      },
    );
    let finish: ((value: Transaction) => void) | undefined;
    api.get.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve as (value: Transaction) => void;
        }) as never,
    );
    const view = await renderHook(() => useTransaction('acc', 'txn'), { wrapper });
    expect(view.result.current.data?.merchantDescription).toBe('From list');
    expect(view.result.current.isPlaceholderData).toBe(true);
    expect(view.result.current.isFetching).toBe(true);
    await act(async () => finish?.(latest));
    await waitFor(() => expect(view.result.current.data?.version).toBe(5));
    expect(view.result.current.data?.merchantDescription).toBe('From server');
    expect(view.result.current.isPlaceholderData).toBe(false);
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}',
      { params: { accountId: 'acc', transactionId: 'txn' } },
    );
  });

  it('requests inactive categories only for historical display', async () => {
    const { wrapper } = setup();
    api.get.mockResolvedValue([] as never);
    const view = await renderHook(
      () => ({
        active: useCategories(),
        history: useCategories(true),
      }),
      { wrapper },
    );
    await waitFor(() => expect(view.result.current.history.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/categories', {
      query: { includeInactive: false },
    });
    expect(api.get).toHaveBeenCalledWith('/api/v1/categories', {
      query: { includeInactive: true },
    });
  });

  it('invalidates the current detail and its history once after category changes', async () => {
    const { client, wrapper } = setup();
    const detail = { id: 'txn', accountId: 'acc', version: 3 } as Transaction;
    client.setQueryData(queryKeys.transaction('acc', 'txn'), detail);
    client.setQueryData(queryKeys.transactionHistory('acc', 'txn'), {
      pages: [{ content: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 1 } }],
      pageParams: [0],
    });
    api.put.mockResolvedValue({ ...detail, version: 4 } as never);
    const view = await renderHook(() => useTransactionDetailActions('acc', 'txn'), { wrapper });
    await act(() =>
      view.result.current.setCategory.mutateAsync({
        categoryId: 'dining',
        version: 3,
      }),
    );
    expect(api.put).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}/category',
      {
        params: { accountId: 'acc', transactionId: 'txn' },
        body: { categoryId: 'dining' },
        version: 3,
      },
    );
    expect(client.getQueryState(queryKeys.transactionHistory('acc', 'txn'))?.isInvalidated).toBe(
      true,
    );
    expect(client.getQueryData<Transaction>(queryKeys.transaction('acc', 'txn'))?.version).toBe(4);
  });

  it('uses one history query key for paginated events and preserves the page-zero boundary', async () => {
    const { wrapper } = setup();
    api.get.mockResolvedValue({
      content: [],
      page: { number: 0, size: 50, totalElements: 0, totalPages: 1 },
    } as never);
    const view = await renderHook(() => useTransactionHistory('acc', 'txn'), { wrapper });
    await waitFor(() => expect(view.result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}/history',
      {
        params: { accountId: 'acc', transactionId: 'txn' },
        query: { page: 0, size: 50, sort: ['occurredAt,desc'] },
      },
    );
  });
});
