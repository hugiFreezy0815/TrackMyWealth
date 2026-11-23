import { collectionPage } from '@/api/collection-page.fixture';
import { setStringAsync } from 'expo-clipboard';
import { QueryClient, QueryClientProvider, type InfiniteData } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { IntlProvider, createIntl, createIntlCache } from 'react-intl';
import { Platform, StyleSheet } from 'react-native';

import { ApiError, NetworkError, setRefreshHandler } from '@/api/client';
import { typedApi } from '@/api/typed-api';
import { queryKeys } from '@/api/queries/keys';
import type { Position, PositionsResponse } from '@/features/portfolio-view/model';
import { amountTexts } from '@/components/shared';
import { restoreSession, signIn, signOut } from '@/features/auth/session';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { showToast } from '@/features/shell/toast';
import en from '@/i18n/en.json';

import { AccountDetail, useAccountRefresh, type AccountDetailProps } from './account-detail';
import type { AccountListItem, AccountSummary, Category, Transaction } from './model';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-clipboard', () => ({ setStringAsync: jest.fn() }));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
  deleteItemAsync: jest.fn(async () => {}),
}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/features/shell/toast', () => ({ showToast: jest.fn() }));
// Jest has no layout: the end marker reports itself visible whenever its layout event fires.
jest.mock('@/features/shell/scroll-events', () => ({
  useWhenOnScreen: (_ref: unknown, onVisible: () => void, enabled: boolean) => () => {
    if (enabled) onVisible();
  },
}));
jest.mock('@/api/typed-api', () => ({
  typedApi: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

const api = jest.mocked(typedApi);
const intl = createIntl({ locale: 'en', messages: en }, createIntlCache());

const CATEGORIES = [
  { id: 'uncat', code: 'UNCATEGORIZED', nameEn: 'Uncategorised', nameDe: 'x', parentId: null },
  { id: 'food', code: 'FOOD', nameEn: 'Food', nameDe: 'x', parentId: null },
  { id: 'groceries', code: 'GROCERIES', nameEn: 'Groceries', nameDe: 'x', parentId: 'food' },
].map((category) => ({ ...category, active: true })) as Category[];

const summary = (other: Partial<AccountSummary> = {}): AccountSummary =>
  ({
    id: 'acc',
    portfolioId: 'p1',
    name: 'Girokonto',
    accountType: 'CASH',
    nativeCurrency: 'EUR',
    holdsPositions: false,
    hasTransactions: true,
    manualValuation: false,
    status: 'ACTIVE',
    archivedAt: null,
    version: 2,
    reconciliation: {
      status: 'NEVER',
      reason: null,
      asOf: null,
      openDifference: null,
      currency: null,
    },
    ...other,
  }) as AccountSummary;

const listItem: AccountListItem = {
  id: 'acc',
  portfolioName: 'Main',
  balance: '4210.55',
  currency: 'EUR',
  workspaceCurrency: 'EUR',
  balanceInWorkspaceCurrency: '4210.55',
  valueKnown: true,
  valueStatus: 'CURRENT',
  asOf: '2026-10-20',
  valueSourceDate: null,
  warnings: [],
} as unknown as AccountListItem;

const holding = (id: string, overrides: Partial<Position> = {}): Position => ({
  securityId: id,
  displayName: `Fund ${id}`,
  isin: null,
  quantity: '10.000000',
  price: '10.00',
  priceCurrency: 'EUR',
  value: '100.00',
  valueInAccountCurrency: '100.00',
  costBasis: '90.00',
  costBasisEstimated: false,
  unrealisedGain: '10.00',
  unrealisedGainPercent: '11.11',
  valueStatus: 'CURRENT',
  ...overrides,
});

const positionSnapshot = (...positions: Position[]): PositionsResponse => ({
  accountId: 'acc',
  currency: 'EUR',
  asOf: '2026-10-20',
  source: 'HOLDINGS_SNAPSHOT',
  complete: true,
  positions,
});

const txn = (index: number, other: Partial<Transaction> = {}): Transaction =>
  ({
    id: `t${String(index).padStart(3, '0')}`,
    accountId: 'acc',
    // Newest first, as the server sorts: one booking per day going back from 1 Sep 2026.
    bookingDate: new Date(Date.UTC(2026, 8, 1) - index * 86_400_000).toISOString().slice(0, 10),
    transactionType: 'CREDIT_CARD_PURCHASE',
    amount: '-10.00',
    currency: 'EUR',
    merchantDescription: `Shop ${String(index).padStart(3, '0')}`,
    counterpartyName: null,
    categoryId: 'food',
    internalTransfer: false,
    voidedAt: null,
    replacesTransactionId: null,
    correctsTransactionId: null,
    reconciliationAdjustment: null,
    amountInAccountCurrency: null,
    fxRateEstimated: false,
    warnings: [],
    version: 0,
    ...other,
  }) as Transaction;

/** An in-memory API behind the mocked typedApi: real hooks, real cache, real invalidation. */
function fakeServer(account: AccountSummary, rows: Transaction[], holdings?: PositionsResponse) {
  type Options = { params?: Record<string, string>; query?: Record<string, unknown> };
  const state: {
    account: AccountSummary;
    rows: Transaction[];
    holdings?: PositionsResponse;
    beforeRead?: (path: string, options: Options) => void | Promise<void>;
  } = { account, rows, holdings };
  const counted = (row: Transaction) => row.categoryId === 'uncat' && !row.internalTransfer;
  const routes: Record<string, (options: Options) => unknown> = {
    '/api/v1/accounts/{accountId}': () => state.account,
    '/api/v1/accounts/{accountId}/positions': () => state.holdings,
    '/api/v1/accounts/{accountId}/transactions/{transactionId}': ({ params }) =>
      state.rows.find((row) => row.id === params?.transactionId) ?? null,
    '/api/v1/accounts': () => collectionPage([listItem]),
    '/api/v1/categories': () => collectionPage(CATEGORIES),
    '/api/v1/accounts/{accountId}/transactions': ({ query = {} }) => {
      const size = Number(query.size);
      const number = Number(query.page);
      const matching = query.uncategorized ? state.rows.filter(counted) : state.rows;
      return {
        content: matching.slice(number * size, (number + 1) * size),
        page: {
          number,
          size,
          totalElements: matching.length,
          totalPages: Math.ceil(matching.length / size),
        },
      };
    },
  };
  api.get.mockImplementation((async (path: string, options: Options = {}) => {
    if (state.beforeRead) await state.beforeRead(path, options);
    return routes[path](options);
  }) as never);
  api.put.mockImplementation((async (
    _path: string,
    options: { params: { transactionId: string }; version: number; body: { categoryId: string } },
  ) => {
    const row = state.rows.find((candidate) => candidate.id === options.params.transactionId)!;
    if (row.version !== options.version) throw new ApiError('conflict', 412);
    Object.assign(row, { categoryId: options.body.categoryId, version: row.version + 1 });
    return row;
  }) as never);
  api.post.mockImplementation((async (path: string, options: { version: number }) => {
    if (options.version !== state.account.version) throw new ApiError('conflict', 412);
    state.account = {
      ...state.account,
      status: path.endsWith('/restore') ? 'ACTIVE' : 'ARCHIVED',
      version: state.account.version + 1,
    };
    return state.account;
  }) as never);
  return state;
}

const listCalls = () =>
  api.get.mock.calls
    .filter(([path, options]) => {
      const query = (options as { query?: { size?: number } } | undefined)?.query;
      return path === '/api/v1/accounts/{accountId}/transactions' && query?.size === 50;
    })
    .map(([, options]) => (options as { query: Record<string, unknown> }).query);

function RefreshableAccountDetail(props: AccountDetailProps) {
  const { refreshing, refresh } = useAccountRefresh();
  return <AccountDetail {...props} refreshing={refreshing} onRefresh={() => void refresh()} />;
}

async function renderDetail(
  props: {
    onBack?: () => void;
    cachedAccounts?: AccountListItem[];
    initialTab?: 'positions' | 'activity';
    staleTime?: number;
  } = {},
) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: props.staleTime },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  const onBack = props.onBack ?? jest.fn();
  const onTransaction = jest.fn();
  if (props.cachedAccounts) client.setQueryData(queryKeys.accounts({}), props.cachedAccounts);
  await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <RefreshableAccountDetail
          accountId="acc"
          initialTab={props.initialTab}
          onBack={onBack}
          onTransaction={onTransaction}
          onImport={jest.fn()}
        />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return { onBack, onTransaction, client };
}

async function changeCategory(currentLabel: string, pick: string) {
  await fireEvent.press(screen.getAllByLabelText(`Change category: ${currentLabel}`)[0]);
  // A long category list scrolls inside the dialog instead of pushing Close off screen.
  const list = await screen.findByTestId('category-picker-list');
  expect(StyleSheet.flatten(list.props.style).maxHeight).toBeGreaterThan(0);
  await fireEvent.press(await screen.findByText(pick));
}

beforeEach(() => {
  api.get.mockReset();
  api.post.mockReset();
  api.put.mockReset();
  jest.mocked(showToast).mockReset();
});

describe('S3 account detail updates (#690)', () => {
  it('reads only the matching masked identifier from the S2 cache without a request', async () => {
    fakeServer(summary(), []);
    const { client } = await renderDetail();
    await screen.findByRole('header', { name: 'Girokonto' });
    expect(screen.queryByText(/••••/)).toBeNull();
    await act(async () => {
      client.setQueryData(queryKeys.portfolioView('p1'), {
        sections: [
          {
            accounts: [
              { accountId: 'other', maskedIdentifier: '•••• 0000' },
              { accountId: 'acc', maskedIdentifier: '•••• 4021' },
            ],
          },
        ],
      });
    });
    expect(await screen.findByText(/Current account · •••• 4021/)).toBeTruthy();
    expect(screen.queryByText(/•••• 0000/)).toBeNull();
    expect(
      api.get.mock.calls.some(([path]) => path === '/api/v1/portfolios/{portfolioId}/view'),
    ).toBe(false);
  });

  it('renders month and day, clamps the purpose and exposes the outgoing label', async () => {
    const purpose = 'Card payment for synthetic reference 402100000000';
    fakeServer(summary(), [txn(0, { counterpartyName: 'Shop', merchantDescription: purpose })]);
    await renderDetail();
    expect(await screen.findByRole('header', { name: 'September 2026' })).toBeTruthy();
    expect(screen.getByText('1 Sep')).toBeTruthy();
    expect(screen.getByText(purpose)).toHaveProp('numberOfLines', 2);
    expect(
      screen.getByRole('button', { name: /Shop.*Outgoing.*synthetic reference/ }),
    ).toBeTruthy();
  });

  it('gives a purpose-only title the two-line purpose preview', async () => {
    const purpose = 'Card payment for synthetic reference 402100000000';
    fakeServer(summary(), [txn(0, { counterpartyName: null, merchantDescription: purpose })]);
    await renderDetail();
    expect(await screen.findByText(purpose)).toHaveProp('numberOfLines', 2);
  });

  it('keeps a counterparty title on one line', async () => {
    fakeServer(summary(), [txn(0, { counterpartyName: 'Shop', merchantDescription: 'Purpose' })]);
    await renderDetail();
    expect(await screen.findByText('Shop')).toHaveProp('numberOfLines', 1);
  });

  it('hides transaction direction when amounts are hidden', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    try {
      fakeServer(summary(), [txn(0)]);
      await renderDetail();
      expect(await screen.findByRole('button', { name: /Shop 000.*hidden/i })).toBeTruthy();
      expect(screen.queryByRole('button', { name: /Shop 000.*Outgoing/ })).toBeNull();
      expect(screen.queryByText('↗')).toBeNull();
    } finally {
      jest.mocked(useAmountsHidden).mockReturnValue(false);
    }
  });

  it.each(['android', 'ios'] as const)('opens native row actions on %s', async (os) => {
    const platform = jest.replaceProperty(Platform, 'OS', os);
    try {
      const purpose = 'Synthetic purpose with the full reference\nSECOND LINE\nTHIRD LINE';
      jest.mocked(setStringAsync).mockClear().mockResolvedValueOnce(true);
      fakeServer(summary(), [txn(0, { counterpartyName: 'Shop', merchantDescription: purpose })]);
      const { onTransaction } = await renderDetail();
      const row = await screen.findByRole('button', { name: /^Shop,/ });
      await fireEvent(row, 'longPress');
      expect(screen.getByRole('button', { name: 'Change category' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Show details' })).toBeEnabled();
      expect(setStringAsync).not.toHaveBeenCalled();
      await fireEvent.press(screen.getByRole('button', { name: 'Copy purpose text' }));
      expect(setStringAsync).toHaveBeenCalledWith(purpose, {
        android: { isSensitive: true },
        ios: { localOnly: true },
      });
      expect(showToast).toHaveBeenCalledWith('account.purposeCopied');

      async function chooseAction(name: string) {
        await fireEvent(row, 'longPress');
        const { onDismiss } = screen.getByTestId('shared-dialog-modal').props;
        await fireEvent.press(screen.getByRole('button', { name }));
        if (os === 'ios') await act(() => onDismiss());
      }

      await chooseAction('Change category');
      expect(screen.getByTestId('category-picker-list')).toBeTruthy();
      await fireEvent.press(screen.getByRole('button', { name: 'Close' }));
      await chooseAction('Show details');
      expect(onTransaction).toHaveBeenCalledWith('t000');
    } finally {
      platform.restore();
    }
  });
});

describe('S3 account detail acceptance checks (#494)', () => {
  it('shows a cached S1 account header with retry when the summary fails offline', async () => {
    api.get.mockRejectedValue(new NetworkError('offline'));
    await renderDetail({
      cachedAccounts: [{ ...listItem, name: 'Cached Girokonto', archived: false }],
    });
    expect(await screen.findByTestId('account-cached-fallback')).toHaveTextContent(
      /Cached Girokonto/,
    );
    expect(screen.getByTestId('account-cached-fallback')).toHaveTextContent(/4.*210.*55/);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it.each([401, 403, 404])(
    'does not disclose cached financial data after HTTP %s',
    async (status) => {
      api.get.mockRejectedValue(new ApiError('access denied', status));
      await renderDetail({
        cachedAccounts: [{ ...listItem, name: 'Private Girokonto', archived: false }],
      });
      expect(screen.queryByText('Private Girokonto')).toBeNull();
      expect(screen.queryByTestId('account-cached-fallback')).toBeNull();
      expect(screen.queryByText("EUR 4'210.55")).toBeNull();
      if (status === 404) {
        expect(
          await screen.findByText('This account no longer exists or is not available to you'),
        ).toBeTruthy();
      } else {
        expect(
          await screen.findByText(
            status === 401
              ? 'Your session has ended. Sign in again.'
              : 'This is not possible right now.',
          ),
        ).toBeTruthy();
      }
    },
  );

  it('hides cached summary and balance if a revalidation is forbidden', async () => {
    fakeServer(summary(), [txn(0)]);
    const { client } = await renderDetail();
    expect(await screen.findByText('Girokonto')).toBeTruthy();
    const originalGet = api.get.getMockImplementation();
    api.get.mockImplementation(((path: string, options: unknown) => {
      if (path === '/api/v1/accounts/{accountId}') {
        return Promise.reject(new ApiError('forbidden', 403));
      }
      return originalGet?.(path as never, options as never);
    }) as never);
    await act(async () => {
      await client.invalidateQueries({ queryKey: queryKeys.account('acc'), exact: true });
    });
    await waitFor(() => expect(screen.queryByText('Girokonto')).toBeNull());
    expect(screen.queryByText("EUR 4'210.55")).toBeNull();
    expect(screen.queryByText('Showing 1 of 1')).toBeNull();
  });

  it('uses the cached financial header on a recoverable 503', async () => {
    api.get.mockRejectedValue(new ApiError('server down', 503));
    await renderDetail({
      cachedAccounts: [{ ...listItem, name: 'Cached Girokonto', archived: false }],
    });
    expect(await screen.findByText('Cached Girokonto')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  it('does not leak a cached account header on a 404', async () => {
    api.get.mockRejectedValue(new ApiError('not found', 404));
    await renderDetail({
      cachedAccounts: [{ ...listItem, name: 'Cached Girokonto', archived: false }],
    });
    expect(
      await screen.findByText('This account no longer exists or is not available to you'),
    ).toBeTruthy();
    expect(screen.queryByText('Cached Girokonto')).toBeNull();
  });

  it('displays S2 position details for known, estimated, missing and foreign-cost holdings', async () => {
    const positions = positionSnapshot(
      holding('regular'),
      holding('estimated', { costBasisEstimated: true, valueStatus: 'ESTIMATED' }),
      holding('missing', { costBasis: null, unrealisedGain: null, unrealisedGainPercent: null }),
      holding('foreign', {
        priceCurrency: 'USD',
        costBasis: '90.00',
        unrealisedGain: null,
        unrealisedGainPercent: null,
      }),
    );
    fakeServer(summary({ holdsPositions: true }), [txn(0)], positions);
    await renderDetail({ initialTab: 'positions' });
    expect(await screen.findByText('Fund regular')).toBeTruthy();
    expect(screen.getAllByText('10 units').length).toBeGreaterThan(0);
    expect(screen.getAllByText('+11.1%').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Estimated cost basis').length).toBeGreaterThan(0);
    expect(screen.getByText('No cost basis yet')).toBeTruthy();
    expect(screen.getByText('Gain not available in USD')).toBeTruthy();
    expect(screen.getAllByText('Price').length).toBeGreaterThan(0);
    expect(screen.getByText('Fund foreign')).toBeTruthy();
    await fireEvent.press(screen.getByText('Activity'));
    expect(await screen.findByText('Showing 1 of 1')).toBeTruthy();
    expect(screen.queryByText('Fund regular')).toBeNull();
  });

  it('shows activity for a cash account even when a link requests positions', async () => {
    fakeServer(summary(), [txn(0)]);
    await renderDetail({ initialTab: 'positions' });
    expect(await screen.findByText('Showing 1 of 1')).toBeTruthy();
    expect(screen.queryByText('Positions')).toBeNull();
    expect(api.get.mock.calls.map(([path]) => path)).not.toContain(
      '/api/v1/accounts/{accountId}/positions',
    );
  });

  it('retains cached transactions and offers Retry when a refetch fails', async () => {
    fakeServer(summary(), [txn(0)]);
    const { client } = await renderDetail();
    expect(await screen.findByText('Showing 1 of 1')).toBeTruthy();
    const originalGet = api.get.getMockImplementation();
    let failTransactions = true;
    api.get.mockImplementation(((path: string, options: unknown) => {
      if (path === '/api/v1/accounts/{accountId}/transactions' && failTransactions) {
        return Promise.reject(new NetworkError('offline'));
      }
      return originalGet?.(path as never, options as never);
    }) as never);
    await act(async () => {
      await client.invalidateQueries({
        queryKey: queryKeys.accountTransactions('acc', {
          uncategorized: false,
          sort: 'bookingDate,desc',
        }),
        exact: true,
      });
    });
    expect(screen.getByText('Showing 1 of 1')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy());
    failTransactions = false;
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull());
  });

  it('loads 120 transactions as 50, 50, 20 without duplicates, then restarts at page 0', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 120 }, (_, index) => txn(index)),
    );
    const { client } = await renderDetail();

    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();
    await fireEvent.press(screen.getByText('Load more'));
    expect(await screen.findByText('Showing 100 of 120')).toBeTruthy();
    await fireEvent.press(screen.getByText('Load more'));
    expect(await screen.findByText('Showing 120 of 120')).toBeTruthy();
    expect(screen.queryByText('Load more')).toBeNull();
    const pages = client.getQueryData<InfiniteData<{ content: Transaction[] }>>(
      queryKeys.accountTransactions('acc', { uncategorized: false, sort: 'bookingDate,desc' }),
    );
    const ids = pages?.pages.flatMap((page) => page.content.map((row) => row.id)) ?? [];
    expect(ids).toHaveLength(120);
    expect(new Set(ids).size).toBe(120);
    expect(listCalls().map((query) => query.page)).toEqual([0, 1, 2]);

    await changeCategory('Food', 'Food › Groceries');

    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();
    expect(listCalls().at(-1)).toMatchObject({ page: 0, uncategorized: false });
  });

  it('loads next pages at the native SectionList end without nested scroll markers', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 120 }, (_, index) => txn(index)),
    );
    await renderDetail();
    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();

    const list = screen.getByTestId('account-transaction-list');
    expect(list.props.initialNumToRender).toBe(12);
    expect(list.props.windowSize).toBe(7);
    await act(async () => fireEvent(list, 'onEndReached'));
    expect(await screen.findByText('Showing 100 of 120')).toBeTruthy();

    await act(async () =>
      fireEvent(screen.getByTestId('account-transaction-list'), 'onEndReached'),
    );
    expect(await screen.findByText('Showing 120 of 120')).toBeTruthy();
    expect(screen.queryByTestId('transactions-end-marker')).toBeNull();
    expect(listCalls().map((query) => query.page)).toEqual([0, 1, 2]);
  });

  it.each(['filter', 'sort'] as const)(
    'reloads only page 0 when returning to a previous %s',
    async (selection) => {
      fakeServer(
        summary(),
        Array.from({ length: 110 }, (_, index) =>
          txn(index, { categoryId: index < 60 ? 'uncat' : 'food' }),
        ),
      );
      await renderDetail({ staleTime: 30_000 });
      expect(await screen.findByText('Showing 50 of 110')).toBeTruthy();
      await fireEvent.press(screen.getByText('Load more'));
      expect(await screen.findByText('Showing 100 of 110')).toBeTruthy();

      if (selection === 'filter') {
        await fireEvent.press(screen.getByTestId('account-needs-category'));
        expect(await screen.findByText('Showing 50 of 60')).toBeTruthy();
      } else {
        await fireEvent.press(screen.getByRole('button', { name: /^Newest first/ }));
        await fireEvent.press(screen.getByRole('radio', { name: 'Oldest first' }));
        await waitFor(() =>
          expect(listCalls().at(-1)).toMatchObject({ page: 0, sort: ['bookingDate,asc'] }),
        );
      }

      const callsBeforeReturn = listCalls().length;
      if (selection === 'filter') {
        await fireEvent.press(screen.getByRole('radio', { name: 'All' }));
      } else {
        await fireEvent.press(await screen.findByRole('button', { name: /^Oldest first/ }));
        await fireEvent.press(screen.getByRole('radio', { name: 'Newest first' }));
      }

      expect(await screen.findByText('Showing 50 of 110')).toBeTruthy();
      expect(screen.queryByText('Showing 100 of 110')).toBeNull();
      await waitFor(() =>
        expect(listCalls().slice(callsBeforeReturn)).toEqual([
          expect.objectContaining({ page: 0, uncategorized: false, sort: ['bookingDate,desc'] }),
        ]),
      );
    },
  );

  it('keeps pagination blocked after a cached page 0 fails to refresh until retry succeeds', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 60 }, (_, index) =>
        txn(index, { categoryId: index ? 'food' : 'uncat' }),
      ),
    );
    await renderDetail({ staleTime: 30_000 });
    expect(await screen.findByText('Showing 50 of 60')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('Showing 60 of 60')).toBeTruthy();

    await fireEvent.press(screen.getByTestId('account-needs-category'));
    expect(await screen.findByText('Showing 1 of 1')).toBeTruthy();

    api.get.mockRejectedValueOnce(new NetworkError('page 0 refresh failed'));
    const callsBeforeReturn = listCalls().length;
    await fireEvent.press(screen.getByRole('radio', { name: 'All' }));
    const retry = await screen.findByRole('button', { name: 'Try again' });
    expect(screen.getByText('Showing 50 of 60')).toBeTruthy();
    expect(screen.getByText('Shop 000')).toBeTruthy();

    const loadMore = screen.getByRole('button', { name: 'Load more' });
    expect(loadMore).toBeDisabled();
    await fireEvent.press(loadMore);
    await fireEvent(screen.getByTestId('account-transaction-list'), 'onEndReached');
    expect(
      listCalls()
        .slice(callsBeforeReturn)
        .map(({ page }) => page),
    ).toEqual([0]);

    await fireEvent.press(retry);
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled();
    });
    await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('Showing 60 of 60')).toBeTruthy();
    expect(
      listCalls()
        .slice(callsBeforeReturn)
        .map(({ page }) => page),
    ).toEqual([0, 0, 1]);
  });

  it.each([
    { kind: 'network error', failure: new NetworkError('server unreachable') },
    { kind: 'gateway timeout', failure: new ApiError('gateway timeout', 504) },
  ])('uses one retry for a refresh outage ($kind)', async ({ failure }) => {
    const server = fakeServer(
      summary(),
      Array.from({ length: 60 }, (_, index) => txn(index)),
    );
    await renderDetail();
    expect(await screen.findByText('Girokonto')).toBeTruthy();
    expect(await screen.findByText('Showing 50 of 60')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('Showing 60 of 60')).toBeTruthy();

    server.beforeRead = () => {
      throw failure;
    };
    await fireEvent(screen.getByTestId('account-transaction-list'), 'refresh');
    await waitFor(() => {
      expect(listCalls().map(({ page }) => page)).toEqual([0, 1, 0]);
      expect(screen.getByRole('button', { name: 'Load more' })).toBeDisabled();
      expect(screen.getAllByText(/^Something went wrong/)).toHaveLength(1);
      expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);
    });
    expect(screen.getByText('Shop 000')).toBeTruthy();
    expect(screen.getByTestId('account-value')).toBeTruthy();
    expect(screen.getByText('Showing 50 of 60')).toBeTruthy();

    server.beforeRead = undefined;
    api.get.mockClear();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => {
      expect(screen.queryByText(/^Something went wrong/)).toBeNull();
      expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
      expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled();
    });
    expect(api.get.mock.calls.map(([path]) => path).sort()).toEqual([
      '/api/v1/accounts',
      '/api/v1/accounts/{accountId}',
      '/api/v1/accounts/{accountId}/transactions',
      '/api/v1/accounts/{accountId}/transactions',
    ]);
    expect(listCalls().map(({ page }) => page)).toEqual([0]);
  });

  it.each(['activity', 'positions'] as const)(
    'recovers both depot tabs with one retry when refresh starts on %s',
    async (initialTab) => {
      const server = fakeServer(
        summary({ accountType: 'DEPOT', holdsPositions: true }),
        Array.from({ length: 60 }, (_, index) => txn(index)),
        positionSnapshot(holding('original')),
      );
      const { client } = await renderDetail({ initialTab });
      expect(await screen.findByText('Girokonto')).toBeTruthy();
      await waitFor(() => {
        expect(client.getQueryState(queryKeys.accountPositions('acc'))?.status).toBe('success');
        expect(client.getQueryState(queryKeys.uncategorizedCount('acc'))?.status).toBe('success');
        expect(listCalls().map(({ page }) => page)).toEqual([0]);
        expect(client.isFetching()).toBe(0);
      });

      server.beforeRead = () => {
        throw new NetworkError('server unreachable');
      };
      await fireEvent(screen.getByTestId('account-transaction-list'), 'refresh');
      await waitFor(() => {
        expect(client.getQueryState(queryKeys.accountPositions('acc'))?.status).toBe('error');
        expect(client.getQueryState(queryKeys.uncategorizedCount('acc'))?.status).toBe('error');
        expect(listCalls().map(({ page }) => page)).toEqual([0, 0]);
        expect(client.isFetching()).toBe(0);
        expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);
      });

      let finishPageZero: () => void = () => {};
      const pageZero = new Promise<void>((resolve) => (finishPageZero = resolve));
      server.beforeRead = (path, { query }) => {
        if (path === '/api/v1/accounts/{accountId}/transactions' && query?.size === 50) {
          return pageZero;
        }
      };
      server.holdings = positionSnapshot(holding('recovered'));
      server.rows[0] = txn(0, {
        categoryId: 'uncat',
        merchantDescription: 'Recovered transaction',
      });
      api.get.mockClear();

      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      if (initialTab === 'activity') {
        await fireEvent.press(screen.getByRole('radio', { name: 'Positions' }));
        expect(await screen.findByText('Fund recovered')).toBeTruthy();
      }
      await fireEvent.press(screen.getByRole('radio', { name: 'Activity' }));
      expect(await screen.findByRole('radio', { name: 'Uncategorised 1' })).toBeTruthy();
      expect(screen.getByText('Shop 000')).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Load more' })).toBeDisabled();
      expect(listCalls().map(({ page }) => page)).toEqual([0]);
      expect(api.get.mock.calls.map(([path]) => path).sort()).toEqual([
        '/api/v1/accounts',
        '/api/v1/accounts/{accountId}',
        '/api/v1/accounts/{accountId}/positions',
        '/api/v1/accounts/{accountId}/transactions',
        '/api/v1/accounts/{accountId}/transactions',
      ]);

      await act(async () => finishPageZero());
      expect(await screen.findByText('Recovered transaction')).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled();
      await fireEvent.press(screen.getByRole('button', { name: 'Load more' }));
      expect(await screen.findByText('Showing 60 of 60')).toBeTruthy();
      expect(listCalls().map(({ page }) => page)).toEqual([0, 1]);
      await fireEvent.press(screen.getByRole('radio', { name: 'Positions' }));
      expect(screen.getByText('Fund recovered')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    },
  );

  it('uses one retry to recover concurrent reads after the session renewal cooldown', async () => {
    const realApi =
      jest.requireActual<typeof import('@/api/typed-api')>('@/api/typed-api').typedApi;
    api.get.mockImplementation(realApi.get);
    api.post.mockImplementation(realApi.post);
    const tokens = (id: string) => ({ accessToken: `access-${id}`, refreshToken: `refresh-${id}` });
    const realNow = Date.now.bind(Date);
    let clockOffset = 0;
    const clock = jest.spyOn(Date, 'now').mockImplementation(() => realNow() + clockOffset);
    let expired = false;
    let renewalAvailable = false;
    const renew = jest.fn(() =>
      renewalAvailable
        ? new Response(JSON.stringify(tokens('new')))
        : new Response(null, { status: 503, headers: { 'Retry-After': '10' } }),
    );
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const json = (data: unknown) => new Response(JSON.stringify(data));
      if (url.pathname === '/api/v1/auth/login') return json({ tokens: tokens('old') });
      if (url.pathname === '/api/v1/auth/refresh') return renew();
      if (url.pathname === '/api/v1/users/me') return json({ sessionId: 'test-session' });
      if (url.pathname === '/api/v1/sessions/test-session/revoke') {
        return new Response(null, { status: 204 });
      }
      if (expired && new Headers(init?.headers).get('Authorization') !== 'Bearer access-new') {
        return new Response(null, { status: 401 });
      }
      if (url.pathname === '/api/v1/accounts') return json(collectionPage([listItem]));
      if (url.pathname === '/api/v1/accounts/acc') return json(summary());
      if (url.pathname === '/api/v1/categories') return json(collectionPage(CATEGORIES));
      if (url.pathname === '/api/v1/accounts/acc/transactions') {
        const isCount = url.searchParams.get('size') === '1';
        const row = txn(0, { merchantDescription: expired ? 'Recovered session row' : 'Shop 000' });
        return json({
          content: isCount ? [] : [row],
          page: { number: 0, totalElements: isCount ? 0 : 1, totalPages: 1 },
        });
      }
      return new Response(null, { status: 404 });
    });

    try {
      await restoreSession();
      await signIn('member@example.test', 'test-password');
      await renderDetail();
      expect(await screen.findByText('Girokonto')).toBeTruthy();
      expect(await screen.findByText('Shop 000')).toBeTruthy();

      expired = true;
      await fireEvent(screen.getByTestId('account-transaction-list'), 'refresh');
      const message = intl.formatMessage({ id: 'problem.reaction.RETRY_LATER' });
      await waitFor(() => {
        expect(screen.getAllByText(message)).toHaveLength(1);
        expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);
        expect(renew).toHaveBeenCalledTimes(1);
      });

      renewalAvailable = true;
      clockOffset = 10_001;
      api.get.mockClear();
      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      expect(await screen.findByText('Recovered session row')).toBeTruthy();
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull());
      expect(screen.queryByText(message)).toBeNull();
      expect(renew).toHaveBeenCalledTimes(2);
      expect(api.get.mock.calls.map(([path]) => path).sort()).toEqual([
        '/api/v1/accounts',
        '/api/v1/accounts/{accountId}',
        '/api/v1/accounts/{accountId}/transactions',
        '/api/v1/accounts/{accountId}/transactions',
        '/api/v1/users/me',
      ]);
    } finally {
      await signOut();
      setRefreshHandler(null);
      fetchMock.mockRestore();
      clock.mockRestore();
    }
  });

  it('keeps a count-specific problem inline during and after recovery from a shared outage', async () => {
    const server = fakeServer(summary(), [txn(0)]);
    await renderDetail();
    expect(await screen.findByText('Shop 000')).toBeTruthy();

    const countProblem = new ApiError('forbidden', 403, {
      code: 'FORBIDDEN',
      status: 403,
      title: 'Forbidden',
    });
    const countMessage = intl.formatMessage({ id: 'problem.FORBIDDEN' });
    let offline = true;
    server.beforeRead = (path, { query }) => {
      if (path === '/api/v1/accounts/{accountId}/transactions' && query?.size === 1) {
        throw countProblem;
      }
      if (offline) throw new NetworkError('server unreachable');
    };
    await fireEvent(screen.getByTestId('account-transaction-list'), 'refresh');
    expect(await screen.findByText(countMessage)).toBeTruthy();
    expect(screen.getAllByText(/^Something went wrong/)).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);

    offline = false;
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull());
    expect(screen.queryByText(/^Something went wrong/)).toBeNull();
    expect(screen.getByText(countMessage)).toBeTruthy();
    expect(screen.getByText('Shop 000')).toBeTruthy();
    expect(screen.getByTestId('account-value')).toBeTruthy();
  });

  it('uses a virtualized SectionList with headers inside the scroll owner for 1,000+ rows', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 1250 }, (_, index) => txn(index)),
    );
    await renderDetail();
    const list = await screen.findByTestId('account-transaction-list');
    expect(list.props.maxToRenderPerBatch).toBe(12);
    expect(list.props.windowSize).toBe(7);
    expect(screen.getByText('Girokonto')).toBeTruthy();
    expect(await screen.findByText('Showing 50 of 1250')).toBeTruthy();
    expect(listCalls()).toHaveLength(1);
  });

  it('automatically reloads and retries with the new version after HTTP 428', async () => {
    const state = fakeServer(summary(), [txn(0, { version: 2 })]);
    await renderDetail();
    await screen.findByText('Showing 1 of 1');
    state.rows[0] = { ...state.rows[0], version: 3 };
    api.put.mockRejectedValueOnce(
      new ApiError('version required', 428, {
        status: 428,
        title: 'Precondition required',
        code: 'VERSION_REQUIRED',
      }),
    );
    await changeCategory('Food', 'Food › Groceries');
    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(2));
    expect(
      api.put.mock.calls.map(([, options]) => (options as { version: number }).version),
    ).toEqual([2, 3]);
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}',
      { params: { accountId: 'acc', transactionId: 't000' } },
    );
  });

  it('reloads a transaction after 412 and the member can retry at the new version', async () => {
    const state = fakeServer(summary(), [txn(0, { version: 2 })]);
    await renderDetail();
    await screen.findByText('Showing 1 of 1');
    state.rows[0] = { ...state.rows[0], version: 3 };
    api.put.mockRejectedValueOnce(
      new ApiError('changed', 412, { status: 412, title: 'Conflict', code: 'VERSION_CONFLICT' }),
    );
    await changeCategory('Food', 'Food › Groceries');
    await fireEvent.press(await screen.findByText('Review and save again'));
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(
        '/api/v1/accounts/{accountId}/transactions/{transactionId}',
        { params: { accountId: 'acc', transactionId: 't000' } },
      ),
    );
    await waitFor(() => expect(screen.getByText(/Food › Groceries/)).toBeTruthy());
    await fireEvent.press(screen.getByText(/Food › Groceries/));
    await waitFor(() =>
      expect(api.put).toHaveBeenLastCalledWith(
        '/api/v1/accounts/{accountId}/transactions/{transactionId}/category',
        expect.objectContaining({ version: 3, body: { categoryId: 'groceries' } }),
      ),
    );
  });

  it('counts uncategorised from the server: 3, then 2 after a change, filter kept', async () => {
    fakeServer(summary(), [
      txn(0, { categoryId: 'uncat' }),
      txn(1, { categoryId: 'uncat' }),
      txn(2, { categoryId: 'uncat' }),
      txn(3),
    ]);
    await renderDetail();

    expect(await screen.findByText('Uncategorised 3')).toBeTruthy();
    expect(screen.getByTestId('account-needs-category')).toHaveTextContent(
      /3 transactions need a category.*never hidden/,
    );
    await fireEvent.press(screen.getByTestId('account-needs-category'));
    expect(await screen.findByText('Showing 3 of 3')).toBeTruthy();

    await changeCategory('Uncategorised', 'Food');

    // The selected chip also shows its check mark.
    expect(await screen.findByText(/^Uncategorised 2\b/)).toBeTruthy();
    expect(await screen.findByText('Showing 2 of 2')).toBeTruthy();
    expect(listCalls().at(-1)).toMatchObject({ page: 0, uncategorized: true });
  });

  it('falls back to All once the last uncategorised transaction gets a category', async () => {
    fakeServer(summary(), [txn(0, { categoryId: 'uncat' }), txn(1), txn(2)]);
    await renderDetail();

    await fireEvent.press(await screen.findByTestId('account-needs-category'));
    expect(await screen.findByText('Showing 1 of 1')).toBeTruthy();
    await changeCategory('Uncategorised', 'Food');

    expect(await screen.findByText('Showing 3 of 3')).toBeTruthy();
    expect(screen.queryByText(/^Uncategorised \d/)).toBeNull();
    expect(screen.queryByText('No uncategorised transactions')).toBeNull();
    expect(listCalls().at(-1)).toMatchObject({ page: 0, uncategorized: false });
  });

  it('shows transfers, dividends, voided rows and reversals as the spec says', async () => {
    fakeServer(summary(), [
      txn(0, {
        transactionType: 'TRANSFER',
        merchantDescription: 'Transfer to savings',
        internalTransfer: true,
        categoryId: 'uncat',
      }),
      txn(1, { transactionType: 'DIVIDEND', merchantDescription: 'Nestlé', categoryId: null }),
      txn(2, { id: 'rev', merchantDescription: 'Storno Migros', replacesTransactionId: 'orig' }),
      txn(3, {
        id: 'orig',
        merchantDescription: 'Migros',
        amount: '-84.35',
        voidedAt: '2026-09-01T08:00:00Z',
      }),
    ]);
    await renderDetail();

    const transfer = await screen.findByLabelText(/^Transfer to savings,/);
    expect(transfer.props.accessibilityLabel).toMatch(/Internal transfer · excluded/);
    expect(transfer.props.accessibilityLabel).not.toMatch(/Uncategorised/);
    expect(screen.queryByText(/^Uncategorised \d/)).toBeNull();

    const dividend = screen.getByLabelText(/^Nestlé,/);
    expect(dividend.props.accessibilityLabel).toMatch(/, Dividend,/);
    expect(screen.queryByLabelText(/^Change category: .*Nestlé/)).toBeNull();
    expect(screen.getAllByLabelText(/^Change category:/)).toHaveLength(3);

    const voided = screen.getByLabelText(/^Migros,/);
    expect(voided.props.accessibilityLabel).toMatch(/voided, not counted/);
    expect(within(voided).getByText('Voided')).toBeTruthy();
    const amount = amountTexts(intl, { value: '-84.35', currency: 'EUR', signRole: 'change' });
    expect(voided.props.accessibilityLabel).toContain(amount.label);
    expect(
      StyleSheet.flatten(within(voided).getByText(amount.display).props.style).textDecorationLine,
    ).toBe('line-through');
    expect(screen.getByText('Reversal of 29 Aug 2026')).toBeTruthy();
    expect(api.get).not.toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}',
      expect.anything(),
    );

    // Card payments read their type, never the API code.
    expect(screen.queryByText(/CREDIT_CARD_PURCHASE/)).toBeNull();
    expect(screen.getByText('Main · Current account')).toBeTruthy();
  });

  it('shows the original booking date before the original transaction page is loaded', async () => {
    fakeServer(summary(), [
      txn(0, {
        merchantDescription: 'Reversed booking',
        replacesTransactionId: 'original',
        categoryId: null,
      }),
      ...Array.from({ length: 49 }, (_, index) => txn(index + 1)),
      txn(50, {
        id: 'original',
        bookingDate: '2026-03-05',
        voidedAt: '2026-09-01T08:00:00Z',
      }),
    ]);
    await renderDetail();

    expect(await screen.findByText('Reversal of 5 Mar 2026', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByLabelText(/^Reversed booking,/).props.accessibilityLabel).toContain(
      'Reversal of 5 Mar 2026',
    );
    expect(listCalls()).toHaveLength(1);
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/transactions/{transactionId}',
      { params: { accountId: 'acc', transactionId: 'original' }, signal: expect.any(AbortSignal) },
    );
  });

  it('retains the reversal row when the original cannot be read', async () => {
    const state = fakeServer(summary(), [
      txn(0, { merchantDescription: 'Reversed booking', replacesTransactionId: 'original' }),
    ]);
    const readOriginal = jest.fn(() => {
      throw new ApiError('not found', 404);
    });
    state.beforeRead = (path) => {
      if (path === '/api/v1/accounts/{accountId}/transactions/{transactionId}') readOriginal();
    };
    const { client } = await renderDetail();

    await waitFor(() =>
      expect(client.getQueryData(queryKeys.reversalDate('acc', 'original'))).toBeNull(),
    );
    expect(screen.getByText('Reversal')).toBeTruthy();
    expect(screen.queryByText(/^Reversal of /)).toBeNull();
    expect(readOriginal).toHaveBeenCalledTimes(1);
  });

  it.each([404, 403, 410])(
    'keeps a known reversal date on a temporary failure and clears it on HTTP %i',
    async (status) => {
      const state = fakeServer(summary(), [
        txn(0, { replacesTransactionId: 'original' }),
        ...Array.from({ length: 49 }, (_, index) => txn(index + 1)),
        txn(50, { id: 'original', bookingDate: '2026-03-05' }),
      ]);
      const { client } = await renderDetail();
      const key = queryKeys.reversalDate('acc', 'original');
      const date = 'Reversal of 5 Mar 2026';
      expect(await screen.findByText(date, {}, { timeout: 5000 })).toBeTruthy();

      async function revalidateWith(failure: Error) {
        state.beforeRead = (path) => {
          if (path.endsWith('/{transactionId}')) throw failure;
        };
        await act(async () => {
          await client.invalidateQueries({ queryKey: key, exact: true });
        });
      }

      await revalidateWith(new NetworkError('lookup timed out'));
      expect(client.getQueryState(key)?.status).toBe('error');
      expect(screen.getByText(date)).toBeTruthy();
      await revalidateWith(new ApiError('unavailable', status));
      expect(await screen.findByText('Reversal')).toBeTruthy();
      expect(screen.queryByText(date)).toBeNull();
      await revalidateWith(new NetworkError('lookup timed out again'));
      expect(screen.queryByText(date)).toBeNull();
    },
  );

  it('finishes a refresh and closes the category picker while a reversal lookup is pending', async () => {
    const state = fakeServer(summary(), [
      txn(0, { replacesTransactionId: 'original', categoryId: null }),
      txn(1),
    ]);
    let release = () => {};
    const lookup = new Promise<void>((resolve) => {
      release = resolve;
    });
    state.beforeRead = (path) => {
      if (path.endsWith('/{transactionId}')) return lookup;
    };
    const { client } = await renderDetail();
    const key = queryKeys.reversalDate('acc', 'original');
    const list = () => screen.getByTestId('account-transaction-list');
    try {
      await waitFor(() => expect(client.getQueryState(key)?.fetchStatus).toBe('fetching'));
      await fireEvent(list(), 'refresh');
      await waitFor(() => {
        expect(listCalls()).toHaveLength(2);
        expect(list().props.refreshControl.props.refreshing).toBe(false);
      });
      expect(client.getQueryState(key)?.fetchStatus).toBe('fetching');

      await changeCategory('Food', 'Food › Groceries');
      await waitFor(() => {
        expect(listCalls()).toHaveLength(3);
        expect(screen.queryByTestId('category-picker-list')).toBeNull();
      });
      expect(client.getQueryState(key)?.fetchStatus).toBe('fetching');
      expect(screen.getByLabelText('Change category: Groceries')).toBeTruthy();
    } finally {
      await act(async () => release());
    }
  });

  it('limits a 50-reversal burst and pages while the original reads are held open', async () => {
    const state = fakeServer(summary(), [
      ...Array.from({ length: 50 }, (_, index) =>
        txn(index, { replacesTransactionId: `original-${index}` }),
      ),
      ...Array.from({ length: 50 }, (_, index) => txn(index + 50)),
      ...Array.from({ length: 50 }, (_, index) => txn(index + 100, { id: `original-${index}` })),
    ]);
    let release = () => {};
    const lookup = new Promise<void>((resolve) => {
      release = resolve;
    });
    let started = 0;
    let active = 0;
    let peak = 0;
    state.beforeRead = async (path) => {
      if (!path.endsWith('/{transactionId}')) return;
      started += 1;
      active += 1;
      peak = Math.max(peak, active);
      await lookup;
      active -= 1;
    };
    await renderDetail();
    try {
      expect(await screen.findByText('Showing 50 of 150')).toBeTruthy();
      await waitFor(() => expect(active).toBe(4));
      await fireEvent(screen.getByTestId('account-transaction-list'), 'onEndReached');
      expect(await screen.findByText('Showing 100 of 150')).toBeTruthy();
      expect(started).toBe(4);
      expect(listCalls().map(({ page }) => page)).toEqual([0, 1]);
    } finally {
      await act(async () => release());
    }
    await waitFor(() => expect(started).toBe(50));
    expect(peak).toBe(4);
  });

  it('shows an open difference with an icon and text, not colour alone', async () => {
    fakeServer(
      summary({
        reconciliation: {
          status: 'OPEN_DIFFERENCE',
          reason: null,
          asOf: '2026-09-30',
          openDifference: '12.50',
          currency: 'EUR',
        },
      }),
      [txn(0)],
    );
    await renderDetail();

    expect(await screen.findByTestId('account-reconciliation')).toHaveTextContent(
      /^⚠ Differs from the statement of 30 Sep 2026 by .*12\.50/,
    );
  });

  it('clears the opening date via If-Match, preserves other fields, and invalidates both histories', async () => {
    const state = fakeServer(
      summary({
        openedAt: '2025-01-01',
        closedAt: '2026-01-01',
        jurisdiction: 'CH',
      }),
      [],
    );
    api.put.mockImplementationOnce(async () => {
      state.account = { ...state.account, openedAt: null, version: 3 };
      return state.account as never;
    });
    const { client } = await renderDetail();
    const overviewHistory = queryKeys.netWorthHistory('EUR', '1Y');
    const portfolioHistory = queryKeys.portfolioHistory('p1', '1Y');
    client.setQueryData(overviewHistory, {});
    client.setQueryData(portfolioHistory, {});

    expect(await screen.findByText('Opened on 1 Jan 2025')).toBeOnTheScreen();
    await fireEvent.press(screen.getByLabelText('Account actions'));
    await fireEvent.press(screen.getByRole('button', { name: 'Edit opening date' }));
    await fireEvent.press(screen.getByRole('button', { name: /^Clear date/ }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save opening date' }));

    await waitFor(() => expect(screen.queryByText('Opened on 1 Jan 2025')).toBeNull());
    expect(api.put).toHaveBeenCalledWith('/api/v1/accounts/{accountId}', {
      params: { accountId: 'acc' },
      version: 2,
      body: {
        name: 'Girokonto',
        accountType: 'CASH',
        nativeCurrency: 'EUR',
        jurisdiction: 'CH',
        closedAt: '2026-01-01',
        openedAt: undefined,
      },
    });
    expect(client.getQueryState(overviewHistory)?.isInvalidated).toBe(true);
    expect(client.getQueryState(portfolioHistory)?.isInvalidated).toBe(true);
  });

  it('shows an opening date rejection on the field and clears it when the date changes', async () => {
    fakeServer(summary({ openedAt: '2025-01-01' }), []);
    api.put.mockRejectedValueOnce(
      new ApiError('invalid', 400, {
        status: 400,
        title: 'Invalid request',
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'openedAt', message: 'Invalid date' }],
      }),
    );
    await renderDetail();
    await fireEvent.press(await screen.findByLabelText('Account actions'));
    await fireEvent.press(screen.getByRole('button', { name: 'Edit opening date' }));

    const date = () => screen.getByRole('button', { name: /^Opened on/ });
    const save = () => screen.getByRole('button', { name: 'Save opening date' });
    await fireEvent.press(screen.getByRole('button', { name: /^Clear date/ }));
    await fireEvent.press(save());
    const message = 'Choose today or an earlier date.';
    expect(await screen.findByText(message)).toBeOnTheScreen();
    expect(date().props.accessibilityHint).toBe(message);

    await fireEvent.press(date());
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText(message)).toBeNull();
  });

  it('keeps the date draft and blocks saves until a failed conflict reload is retried', async () => {
    const state = fakeServer(summary({ openedAt: '2025-01-01' }), []);
    api.put.mockRejectedValueOnce(
      new ApiError('changed', 412, { status: 412, title: 'Conflict', code: 'VERSION_CONFLICT' }),
    );
    await renderDetail();
    await fireEvent.press(await screen.findByLabelText('Account actions'));
    await fireEvent.press(screen.getByRole('button', { name: 'Edit opening date' }));
    await fireEvent.press(screen.getByRole('button', { name: /^Clear date/ }));
    state.account = {
      ...state.account,
      name: 'Renamed',
      jurisdiction: 'CH',
      closedAt: '2025-12-31',
      version: 3,
    };
    await fireEvent.press(screen.getByRole('button', { name: 'Save opening date' }));
    const review = await screen.findByText('Review and save again');
    api.get.mockRejectedValueOnce(new NetworkError('reload failed'));
    await fireEvent.press(review);

    const save = () => screen.getByRole('button', { name: 'Save opening date' });
    const retry = await screen.findByRole('button', { name: 'Try again' });
    expect(save()).toBeDisabled();
    expect(screen.getByRole('button', { name: /Opened on .*Choose a date/ })).toBeOnTheScreen();
    expect(api.put).toHaveBeenCalledTimes(1);

    await fireEvent.press(retry);
    await waitFor(() => expect(save()).toBeEnabled());
    expect(api.put).toHaveBeenCalledTimes(1);
    api.put.mockResolvedValueOnce({ ...state.account, openedAt: null, version: 4 } as never);
    await fireEvent.press(save());
    expect(api.put).toHaveBeenLastCalledWith(
      '/api/v1/accounts/{accountId}',
      expect.objectContaining({
        version: 3,
        body: {
          name: 'Renamed',
          accountType: 'CASH',
          nativeCurrency: 'EUR',
          jurisdiction: 'CH',
          closedAt: '2025-12-31',
          openedAt: undefined,
        },
      }),
    );
  });

  it('disables changes on an archived account and restores with If-Match', async () => {
    fakeServer(summary({ status: 'ARCHIVED', archivedAt: '2026-10-01T10:00:00Z', version: 5 }), [
      txn(0),
    ]);
    await renderDetail();

    expect(await screen.findByTestId('account-archived')).toHaveTextContent(
      /Archived on 1 Oct 2026\..*Restore the account to change it\./,
    );
    expect(await screen.findByLabelText('Change category: Food')).toBeDisabled();
    await fireEvent.press(screen.getByLabelText('Account actions'));
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Edit opening date' })).toBeDisabled();

    await fireEvent.press(screen.getByRole('button', { name: 'Restore account' }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/restore', {
        params: { accountId: 'acc' },
        version: 5,
      }),
    );
  });

  it('offers the savings pocket in the actions menu and reads the account lists only then (#466)', async () => {
    fakeServer(summary({ savingsPocketAccountId: 'pocket' }), [txn(0)]);
    await renderDetail();
    await screen.findByText(/^Savings pocket: /);
    const directoryReads = () =>
      api.get.mock.calls.filter(
        ([path, options]) =>
          path === '/api/v1/accounts' &&
          !(options as { query?: { portfolioId?: string } } | undefined)?.query?.portfolioId,
      );
    expect(directoryReads()).toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Change savings pocket' })).toBeNull();

    await fireEvent.press(screen.getByLabelText('Account actions'));
    await fireEvent.press(await screen.findByRole('button', { name: 'Change savings pocket' }));
    expect(await screen.findByText(/applies to every import you complete/)).toBeOnTheScreen();
    expect(directoryReads()).toHaveLength(2);
  });

  it('goes back with a toast when the account is gone during an action', async () => {
    fakeServer(summary(), [txn(0)]);
    api.post.mockRejectedValue(new ApiError('gone', 404));
    const { onBack } = await renderDetail();

    await fireEvent.press(await screen.findByLabelText('Account actions'));
    await fireEvent.press(screen.getByRole('button', { name: 'Archive account' }));
    const confirm = screen.getAllByRole('button', { name: 'Archive account' }).at(-1)!;
    await fireEvent.press(confirm);

    await waitFor(() => expect(onBack).toHaveBeenCalled());
    expect(showToast).toHaveBeenCalledWith('account.notFound');
  });

  it('explains where the value of an account without a ledger comes from', async () => {
    fakeServer(summary({ hasTransactions: false, manualValuation: true }), []);
    await renderDetail();

    expect(await screen.findByText('Value source not available')).toBeTruthy();
    expect(listCalls()).toEqual([]);
    expect(screen.queryByText('All')).toBeNull();
  });
});
