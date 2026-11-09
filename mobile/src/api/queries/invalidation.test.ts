import { createElement, type ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import {
  type InfiniteData,
  InfiniteQueryObserver,
  onlineManager,
  QueryClient,
  QueryClientProvider,
  QueryObserver,
} from '@tanstack/react-query';

import { typedApi } from '@/api/typed-api';
import { invalidateFinancialData } from './invalidation';
import { queryKeys } from './keys';
import { useCommitImport, useRollbackImport } from './resources';

jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn(), post: jest.fn() } }));
const mockedPost = jest.mocked(typedApi.post);

type Page = { page: { number: number; totalPages: number } };

function newClient() {
  return new QueryClient({
    defaultOptions: {
      // No garbage-collection timers: they would keep Jest running after the tests.
      queries: { retry: false, gcTime: Infinity },
      mutations: { gcTime: Infinity },
    },
  });
}

/** An observed (on-screen) paged list with three loaded pages; returns the pages it fetched. */
async function pagedList(client: QueryClient, key: readonly unknown[]) {
  const fetched: number[] = [];
  const observer = new InfiniteQueryObserver<
    Page,
    Error,
    InfiniteData<Page, number>,
    readonly unknown[],
    number
  >(client, {
    queryKey: key,
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => {
      fetched.push(pageParam);
      return { page: { number: pageParam, totalPages: 3 } };
    },
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
  });
  const unsubscribe = observer.subscribe(() => undefined);
  await waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true));
  await observer.fetchNextPage();
  await observer.fetchNextPage();
  fetched.length = 0;
  return { fetched, observer, unsubscribe };
}

type Wrapper = (props: { children: ReactNode }) => ReturnType<typeof createElement>;

/** Renders each mutation hook and runs it with the version read from the cached batch. */
const MUTATIONS: [string, (wrapper: Wrapper) => Promise<void>][] = [
  [
    'commit',
    async (wrapper) => {
      const { result } = await renderHook(() => useCommitImport('a1', 'b1'), { wrapper });
      await act(async () => {
        await result.current.mutateAsync({ version: 4 });
      });
    },
  ],
  [
    'rollback',
    async (wrapper) => {
      const { result } = await renderHook(() => useRollbackImport('a1', 'b1'), { wrapper });
      await act(async () => {
        await result.current.mutateAsync({ resource: { version: 4 }, reason: 'wrong file' });
      });
    },
  ],
];

describe('financial data invalidation', () => {
  afterEach(() => {
    onlineManager.setOnline(true);
    jest.resetAllMocks();
  });

  it.each(MUTATIONS)(
    'a successful %s invalidates net worth, portfolios, accounts and cash flow',
    async (_, run) => {
      const client = newClient();
      for (const key of [
        queryKeys.netWorth(),
        queryKeys.portfolios,
        queryKeys.accounts({}),
        queryKeys.account('a1'),
        queryKeys.cashFlow('2026-11'),
        queryKeys.importBatch('a1', 'b1'),
        queryKeys.categories(),
      ]) {
        client.setQueryData(key, { cached: true });
      }
      mockedPost.mockResolvedValue({} as never);
      const wrapper: Wrapper = ({ children }) =>
        createElement(QueryClientProvider, { client }, children);
      await run(wrapper);

      const invalidated = (key: readonly unknown[]) =>
        client.getQueryCache().find({ queryKey: key, exact: true })?.state.isInvalidated;
      expect(invalidated(queryKeys.netWorth())).toBe(true);
      expect(invalidated(queryKeys.portfolios)).toBe(true);
      expect(invalidated(queryKeys.accounts({}))).toBe(true);
      expect(invalidated(queryKeys.account('a1'))).toBe(true);
      expect(invalidated(queryKeys.cashFlow('2026-11'))).toBe(true);
      expect(invalidated(queryKeys.importBatch('a1', 'b1'))).toBe(true);
      expect(invalidated(queryKeys.categories())).toBe(false);
      expect(mockedPost).toHaveBeenCalledWith(
        expect.stringMatching(/\/(commit|rollback)$/),
        expect.objectContaining({ version: 4 }),
      );
    },
  );

  it('refetches every invalidated paged list from page 0 only', async () => {
    const client = newClient();
    const committed = await pagedList(client, queryKeys.accountTransactions('a1', {}));
    const other = await pagedList(client, queryKeys.accountTransactions('a2', {}));

    await invalidateFinancialData(client);

    await waitFor(() => expect(committed.observer.getCurrentResult().isFetching).toBe(false));
    await waitFor(() => expect(other.observer.getCurrentResult().isFetching).toBe(false));
    expect(committed.fetched).toEqual([0]);
    expect(other.fetched).toEqual([0]);
    expect(committed.observer.getCurrentResult().data?.pages).toHaveLength(1);
    committed.unsubscribe();
    other.unsubscribe();
  });

  it('pauses the refetch while offline and keeps the cached data rendered', async () => {
    const client = newClient();
    client.mount(); // as QueryClientProvider does: resumes paused queries when back online
    const queryFn = jest.fn(async () => ({ total: '100.00' }));
    const observer = new QueryObserver(client, { queryKey: queryKeys.netWorth(), queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true));
    const list = await pagedList(client, queryKeys.accountTransactions('a1', {}));
    queryFn.mockClear();

    onlineManager.setOnline(false);
    void invalidateFinancialData(client);

    await waitFor(() => expect(observer.getCurrentResult().fetchStatus).toBe('paused'));
    expect(queryFn).not.toHaveBeenCalled();
    expect(observer.getCurrentResult().data).toEqual({ total: '100.00' });
    expect(list.observer.getCurrentResult().data?.pages).toHaveLength(1);

    onlineManager.setOnline(true);
    await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(1));
    unsubscribe();
    list.unsubscribe();
    client.unmount();
  });
});
