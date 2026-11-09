import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { IntlProvider, createIntl, createIntlCache } from 'react-intl';
import { StyleSheet } from 'react-native';

import { ApiError } from '@/api/client';
import { typedApi } from '@/api/typed-api';
import { amountTexts } from '@/components/shared';
import { showToast } from '@/features/shell/toast';
import en from '@/i18n/en.json';

import { AccountDetail } from './account-detail';
import type { AccountListItem, AccountSummary, Category, Transaction } from './model';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
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

const txn = (index: number, other: Partial<Transaction> = {}): Transaction =>
  ({
    id: `t${String(index).padStart(3, '0')}`,
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
function fakeServer(account: AccountSummary, rows: Transaction[]) {
  const state = { account, rows };
  const counted = (row: Transaction) => row.categoryId === 'uncat' && !row.internalTransfer;
  type Options = { params?: Record<string, string>; query?: Record<string, unknown> };
  const routes: Record<string, (options: Options) => unknown> = {
    '/api/v1/accounts/{accountId}': () => state.account,
    '/api/v1/accounts': () => [listItem],
    '/api/v1/categories': () => CATEGORIES,
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
  api.get.mockImplementation((async (path: string, options: Options = {}) =>
    routes[path](options)) as never);
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

async function renderDetail(props: { onBack?: () => void } = {}) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  const onBack = props.onBack ?? jest.fn();
  await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <AccountDetail
          accountId="acc"
          onBack={onBack}
          onTransaction={jest.fn()}
          onImport={jest.fn()}
        />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return { onBack };
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

describe('S3 account detail acceptance checks (#494)', () => {
  it('loads 120 transactions as 50, 50, 20 without duplicates, then restarts at page 0', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 120 }, (_, index) => txn(index)),
    );
    await renderDetail();

    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();
    await fireEvent.press(screen.getByText('Load more'));
    expect(await screen.findByText('Showing 100 of 120')).toBeTruthy();
    await fireEvent.press(screen.getByText('Load more'));
    expect(await screen.findByText('Showing 120 of 120')).toBeTruthy();
    expect(screen.queryByText('Load more')).toBeNull();
    const rows = screen.getAllByLabelText(/^Shop \d{3},/);
    expect(new Set(rows.map((row) => row.props.accessibilityLabel)).size).toBe(120);
    expect(listCalls().map((query) => query.page)).toEqual([0, 1, 2]);

    await changeCategory('Food', 'Food › Groceries');

    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();
    expect(listCalls().at(-1)).toMatchObject({ page: 0, uncategorized: false });
  });

  it('loads the next page on a phone once the last 10 rows come into view', async () => {
    fakeServer(
      summary(),
      Array.from({ length: 120 }, (_, index) => txn(index)),
    );
    await renderDetail();
    expect(await screen.findByText('Showing 50 of 120')).toBeTruthy();

    // The marker sits before row 41 of 50, i.e. 10 rows before the end.
    const marker = screen.getByTestId('transactions-end-marker');
    const rows = screen.getAllByLabelText(/^Shop \d{3},/);
    expect(rows.indexOf(screen.getByLabelText(/^Shop 040,/))).toBe(40);
    await act(async () => fireEvent(marker, 'layout'));
    expect(await screen.findByText('Showing 100 of 120')).toBeTruthy();

    await act(async () => fireEvent(screen.getByTestId('transactions-end-marker'), 'layout'));
    expect(await screen.findByText('Showing 120 of 120')).toBeTruthy();
    expect(screen.queryByTestId('transactions-end-marker')).toBeNull();
    expect(listCalls().map((query) => query.page)).toEqual([0, 1, 2]);
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

    // Card payments read their type, never the API code.
    expect(screen.queryByText(/CREDIT_CARD_PURCHASE/)).toBeNull();
    expect(screen.getByText('Main · Current account · EUR')).toBeTruthy();
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

    await fireEvent.press(screen.getByRole('button', { name: 'Restore account' }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/restore', {
        params: { accountId: 'acc' },
        version: 5,
      }),
    );
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

    expect(
      await screen.findByText('The value of this account comes from a manual valuation'),
    ).toBeTruthy();
    expect(listCalls()).toEqual([]);
    expect(screen.queryByText('All')).toBeNull();
  });
});
