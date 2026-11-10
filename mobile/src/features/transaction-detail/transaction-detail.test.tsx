import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Platform } from 'react-native';
import { IntlProvider } from 'react-intl';
import {
  useAccount,
  useCategories,
  useSecurity,
  useTransaction,
  useTransactionDetailActions,
  useTransactionHistory,
} from '@/api/queries/resources';
import { useConflict } from '@/api/queries/conflict';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import type { components } from '@/api/schema';
import { ApiError } from '@/api/client';
import en from '@/i18n/en.json';
import de from '@/i18n/de.json';
import { TransactionDetail } from './transaction-detail';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/api/queries/conflict', () => ({ useConflict: jest.fn() }));
jest.mock('@/components/shared/accessibility-focus', () => ({
  moveAccessibilityFocus: jest.fn(),
}));
jest.mock('@/api/queries/resources', () => ({
  useAccount: jest.fn(),
  useCategories: jest.fn(),
  useSecurity: jest.fn(),
  useTransaction: jest.fn(),
  useTransactionDetailActions: jest.fn(),
  useTransactionHistory: jest.fn(),
}));

type Transaction = components['schemas']['TransactionResponse'];
type Category = components['schemas']['CategoryResponse'];
const base = {
  id: 'transaction-1',
  accountId: 'account-1',
  accountName: 'Current account',
  portfolioId: 'portfolio-1',
  portfolioName: 'Bank',
  transactionType: 'CARD_PAYMENT',
  bookingDate: '2026-11-08',
  amount: '-84.35',
  currency: 'CHF',
  merchantDescription: 'Migros',
  counterpartyName: 'Migros AG',
  mcc: '5411',
  notes: null,
  source: 'CSV',
  externalId: 'reference-1',
  internalTransfer: false,
  fxRateToAccountCurrency: null,
  fxRateEstimated: false,
  relatedTransactionId: null,
  createdAt: '2026-11-08T10:00:00Z',
  securityId: null,
  quantity: null,
  unitPrice: null,
  feeAmount: null,
  tradeDate: null,
  settlementDate: null,
  grossAmount: null,
  taxWithheldAmount: null,
  netAmount: null,
  categoryId: 'groceries',
  categoryAssignedBy: 'RULE',
  removal: 'VOID',
  reconciliationAdjustment: null,
  voidedAt: null,
  voidReason: null,
  replacesTransactionId: null,
  correctsTransactionId: null,
  restoresTransactionId: null,
  deletedAt: null,
  counterpartyAccountId: null,
  counterpartyAccountName: null,
  amountInAccountCurrency: null,
  bankTransactionCode: 'POS',
  importBatch: null,
  version: 7,
  warnings: [],
} as Transaction;
const categories = [
  { id: 'food', nameEn: 'Food', nameDe: 'Essen', code: 'FOOD', active: true, parentId: null },
  {
    id: 'groceries',
    nameEn: 'Groceries',
    nameDe: 'Lebensmittel',
    code: 'GROCERIES',
    active: true,
    parentId: 'food',
  },
  {
    id: 'dining',
    nameEn: 'Dining out',
    nameDe: 'Restaurant',
    code: 'DINING',
    active: true,
    parentId: 'food',
  },
] as Category[];

const success = (data: unknown) => ({
  data,
  isPending: false,
  isError: false,
  error: null,
  dataUpdatedAt: Date.now(),
  isRefetching: false,
  refetch: jest.fn(),
  hasNextPage: false,
  isFetchNextPageError: false,
  isFetchingNextPage: false,
  fetchNextPage: jest.fn(),
});
const setCategory = jest.fn(async () => base);
const resetCategory = jest.fn(async () => base);
const remove = jest.fn(async () => ({ removal: 'VOID' }));

function fixtures(transaction: Transaction = base) {
  jest
    .mocked(useTransaction)
    .mockReturnValue(success(transaction) as unknown as ReturnType<typeof useTransaction>);
  jest
    .mocked(useAccount)
    .mockReturnValue(
      success({ status: 'ACTIVE', nativeCurrency: 'CHF' }) as unknown as ReturnType<
        typeof useAccount
      >,
    );
  jest
    .mocked(useCategories)
    .mockReturnValue(success(categories) as unknown as ReturnType<typeof useCategories>);
  jest
    .mocked(useSecurity)
    .mockReturnValue(success(undefined) as unknown as ReturnType<typeof useSecurity>);
  jest.mocked(useTransactionHistory).mockReturnValue(
    success({
      pages: [{ content: [], page: { number: 0, totalPages: 1, totalElements: 0, size: 50 } }],
    }) as unknown as ReturnType<typeof useTransactionHistory>,
  );
  jest.mocked(useTransactionDetailActions).mockReturnValue({
    setCategory: { mutateAsync: setCategory, isPending: false },
    resetCategory: { mutateAsync: resetCategory, isPending: false },
    remove: { mutateAsync: remove, isPending: false },
  } as unknown as ReturnType<typeof useTransactionDetailActions>);
  jest.mocked(useConflict).mockReturnValue({
    conflicted: false,
    dismiss: jest.fn(),
    handle: jest.fn(async () => false),
  });
}

function useMockConflict() {
  const [conflicted, setConflicted] = useState(false);
  return {
    conflicted,
    dismiss: () => setConflicted(false),
    handle: async () => {
      setConflicted(true);
      return true;
    },
  };
}

async function show() {
  const onBack = jest.fn();
  const client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
  await render(
    <QueryClientProvider client={client}>
      <IntlProvider locale="en" messages={en}>
        <TransactionDetail accountId="account-1" transactionId="transaction-1" onBack={onBack} />
      </IntlProvider>
    </QueryClientProvider>,
  );
  return onBack;
}

describe('S4 transaction detail', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.mocked(useAmountsHidden).mockReturnValue(false);
    fixtures();
  });

  it.each([401, 403, 404])(
    'hides cached S4 amounts and merchant on account authorization failure HTTP %s',
    async (status) => {
      jest.mocked(useAccount).mockReturnValue({
        ...success({ status: 'ACTIVE', nativeCurrency: 'CHF' }),
        isError: true,
        error: new ApiError('denied', status),
      } as unknown as ReturnType<typeof useAccount>);
      await show();
      expect(screen.queryByTestId('transaction-detail')).toBeNull();
      expect(screen.queryByText('Migros')).toBeNull();
      expect(screen.queryByText(/84.35/)).toBeNull();
      expect(screen.queryByRole('button', { name: 'Change category' })).toBeNull();
    },
  );

  it('shows automatic category provenance and current server values', async () => {
    await show();
    expect(screen.getByText('Migros')).toBeTruthy();
    expect(screen.getByText('Food › Groceries · automatic')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reset category' })).toBeNull();
    expect(screen.getByText('Bank · Current account')).toBeTruthy();
  });

  it('masks trade quantity in visible text and accessibility labels', async () => {
    fixtures({ ...base, transactionType: 'BUY', quantity: '12345.6789' });
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await show();
    const quantity = screen.getByTestId('transaction-quantity');
    expect(quantity.props.children).toBe('••••');
    expect(quantity.props.accessibilityLabel).not.toContain('12345');
    expect(screen.queryByText('12345.6789')).toBeNull();
  });

  it('renders the trade quantity when privacy masking is off', async () => {
    fixtures({ ...base, transactionType: 'BUY', quantity: '12345.6789' });
    await show();
    expect(screen.queryByText('••••')).toBeNull();
    expect(screen.getByText(/12.*345/)).toBeTruthy();
  });

  it('retains cached details and allows retry after a failed refetch', async () => {
    const refetch = jest.fn();
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      isError: true,
      error: new ApiError('Service unavailable', 503),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    await show();
    expect(screen.getByText('Migros')).toBeTruthy();
    const retry = screen.getByRole('button', { name: /Retry/i });
    await fireEvent.press(retry);
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it.each(['loading', 'archived'])('locks S4 writes when account is %s', async (status) => {
    jest.mocked(useAccount).mockReturnValue({
      ...success(status === 'archived' ? { status: 'ARCHIVED' } : undefined),
      isPending: status === 'loading',
    } as unknown as ReturnType<typeof useAccount>);
    await show();
    const category = screen.getByRole('button', { name: 'Change category' });
    expect(category.props.disabled ?? category.props.accessibilityState?.disabled).toBe(true);
    await fireEvent.press(category);
    expect(setCategory).not.toHaveBeenCalled();
  });

  it('shows automatic category provenance and current server values', async () => {
    await show();
    expect(screen.getByText('Migros')).toBeTruthy();
    expect(screen.getByText('Food › Groceries · automatic')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reset category' })).toBeNull();
    expect(screen.getByText('Bank · Current account')).toBeTruthy();
  });

  it('sends the server ETag version with a category override', async () => {
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() =>
      expect(setCategory).toHaveBeenCalledWith({ categoryId: 'dining', version: 7 }),
    );
  });

  it('reloads the ETag and retries VERSION_REQUIRED once without losing the chosen category', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('version required', 428, {
        code: 'VERSION_REQUIRED',
        status: 428,
        title: 'Version required',
      }),
    );
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch: jest.fn(async () => ({ data: { ...base, version: 8 } })),
    } as unknown as ReturnType<typeof useTransaction>);
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => {
      expect(setCategory).toHaveBeenNthCalledWith(1, { categoryId: 'dining', version: 7 });
      expect(setCategory).toHaveBeenNthCalledWith(2, { categoryId: 'dining', version: 8 });
    });
  });

  it('does not retry a 428 when the refetch fails but still returns stale cached data', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('version required', 428, {
        code: 'VERSION_REQUIRED',
        status: 428,
        title: 'Version required',
      }),
    );
    const refetchError = new Error('network unavailable');
    const refetch = jest.fn(async () => ({ data: base, error: refetchError }));
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    expect(await screen.findByText(/Couldn't save the category/)).toBeTruthy();
    expect(setCategory).toHaveBeenCalledTimes(1);
    expect(refetch).toHaveBeenCalledTimes(1);
    expect(screen.getByText('✓ Food › Dining out')).toBeTruthy();
  });

  it('does not retry a 428 with a placeholder response', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('version required', 428, {
        code: 'VERSION_REQUIRED',
        status: 428,
        title: 'Version required',
      }),
    );
    const refetch = jest.fn(async () => ({
      data: { ...base, version: 8 },
      isPlaceholderData: true,
    }));
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    expect(await screen.findByText(/Couldn't save the category/)).toBeTruthy();
    expect(setCategory).toHaveBeenCalledTimes(1);
  });

  it('routes a 404 during the 428 refetch to the not-found recovery', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('version required', 428, {
        code: 'VERSION_REQUIRED',
        status: 428,
        title: 'Version required',
      }),
    );
    const missing = new ApiError('transaction missing', 404, {
      code: 'NOT_FOUND',
      status: 404,
      title: 'Not found',
    });
    const refetch = jest.fn(async () => ({ data: base, error: missing }));
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    const onBack = await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    expect(
      await screen.findByText('This transaction no longer exists or is unavailable to you'),
    ).toBeTruthy();
    expect(setCategory).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByRole('button', { name: 'Back to account' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['en', en, 'Before the opening balance: not counted in the balance', 'Check this transaction'],
    ['de-CH', de, 'Vor dem Anfangssaldo: zählt nicht zum Saldo', 'Prüfe diese Transaktion'],
  ])(
    'reuses S3 warning translations and deduplicates unknown warnings in %s',
    async (locale, messages, known, unknown) => {
      fixtures({
        ...base,
        warnings: ['BOOKED_BEFORE_OPENING_BALANCE', 'UNKNOWN_ONE', 'UNKNOWN_TWO'],
      });
      await render(
        <QueryClientProvider client={new QueryClient()}>
          <IntlProvider locale={locale} messages={messages}>
            <TransactionDetail
              accountId="account-1"
              transactionId="transaction-1"
              onBack={jest.fn()}
            />
          </IntlProvider>
        </QueryClientProvider>,
      );
      expect(screen.getByText(`⚠ ${known}`)).toBeTruthy();
      expect(screen.getAllByText(`⚠ ${unknown}`)).toHaveLength(1);
    },
  );

  it('does not refocus the same detail panel when its transaction data refreshes', async () => {
    const { moveAccessibilityFocus } = jest.requireMock(
      '@/components/shared/accessibility-focus',
    ) as { moveAccessibilityFocus: jest.Mock };
    const client = new QueryClient();
    const view = await render(
      <QueryClientProvider client={client}>
        <IntlProvider locale="en" messages={en}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
          />
        </IntlProvider>
      </QueryClientProvider>,
    );
    if (Platform.OS === 'web') expect(moveAccessibilityFocus).toHaveBeenCalledTimes(1);
    const originalFocusCalls = moveAccessibilityFocus.mock.calls.length;
    jest
      .mocked(useTransaction)
      .mockReturnValue(
        success({ ...base, version: 8 }) as unknown as ReturnType<typeof useTransaction>,
      );
    await view.rerender(
      <QueryClientProvider client={client}>
        <IntlProvider locale="en" messages={en}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
          />
        </IntlProvider>
      </QueryClientProvider>,
    );
    expect(moveAccessibilityFocus).toHaveBeenCalledTimes(originalFocusCalls);
  });

  it('shows reset only for user overrides, and sends the original ETag version', async () => {
    fixtures({ ...base, categoryAssignedBy: 'USER' });
    await show();
    expect(screen.getByText('Food › Groceries · by you')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Reset category' }));
    await waitFor(() => expect(resetCategory).toHaveBeenCalledWith({ version: 7 }));
  });

  it('requires the VOID explanation before deleting an imported row', async () => {
    const back = await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Delete transaction' }));
    expect(screen.getByText(/Void this imported transaction/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Confirm deletion' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith({ version: 7 }));
    expect(back).toHaveBeenCalled();
  });

  it('hides delete for nonremovable or reconciliation rows', async () => {
    fixtures({ ...base, removal: null, reconciliationAdjustment: 'ADJUSTMENT' });
    await show();
    expect(screen.queryByRole('button', { name: 'Delete transaction' })).toBeNull();
    expect(screen.getByText('Change this through reconciliation.')).toBeTruthy();
  });

  it('renders dividend trade values without a category picker', async () => {
    fixtures({
      ...base,
      transactionType: 'DIVIDEND',
      categoryId: null,
      securityId: 'security-1',
      grossAmount: '100.00',
      taxWithheldAmount: '15.00',
      netAmount: '85.00',
    });
    await show();
    expect(screen.queryByRole('button', { name: 'Change category' })).toBeNull();
    expect(screen.getByText('Gross amount')).toBeTruthy();
    expect(screen.getByText('Tax withheld')).toBeTruthy();
    expect(screen.getByText('Net amount')).toBeTruthy();
  });
  it('keeps a changed selection local until explicit Save', async () => {
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    expect(setCategory).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() =>
      expect(setCategory).toHaveBeenCalledWith({ categoryId: 'dining', version: 7 }),
    );
  });

  it('preserves the selected category and displays a local error on network failure', async () => {
    setCategory.mockRejectedValueOnce(new Error('offline'));
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(screen.getByText(/Couldn't save the category/)).toBeTruthy());
    expect(screen.getByText('✓ Food › Dining out')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(setCategory).toHaveBeenCalledTimes(2));
  });

  it('disables mutation actions while the S3 placeholder is being revalidated', async () => {
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      isPlaceholderData: true,
      isFetching: true,
    } as unknown as ReturnType<typeof useTransaction>);
    await show();
    expect(
      screen.getByRole('button', { name: 'Change category' }).props.accessibilityState?.disabled ??
        screen.getByRole('button', { name: 'Change category' }).props.disabled,
    ).toBe(true);
    expect(setCategory).not.toHaveBeenCalled();
  });

  it('closes the picker on a 412, preserves the choice, and saves using the reviewed server ETag', async () => {
    jest.mocked(useConflict).mockImplementation(useMockConflict);
    const refetch = jest.fn(async () => ({ data: { ...base, version: 8 } }));
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    jest.mocked(useCategories).mockReturnValue({
      ...success(categories),
      refetch: jest.fn(async () => ({ data: categories })),
    } as unknown as ReturnType<typeof useCategories>);
    setCategory.mockRejectedValueOnce(
      new ApiError('conflict', 412, {
        code: 'VERSION_CONFLICT',
        status: 412,
        title: 'Concurrent edit',
      }),
    );

    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(screen.getByText('This item was changed meanwhile')).toBeTruthy());
    expect(screen.queryByRole('radio', { name: 'Food › Dining out' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Review and save again' }));
    await waitFor(() => expect(refetch).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.getByText(/Your pending choice: Food › Dining out/)).toBeTruthy(),
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Save again' }));
    await waitFor(() =>
      expect(setCategory).toHaveBeenNthCalledWith(2, { categoryId: 'dining', version: 8 }),
    );
  });

  it('blocks saving until conflict review reload succeeds, and offers Retry review', async () => {
    jest.mocked(useConflict).mockImplementation(useMockConflict);
    const refetch = jest
      .fn()
      .mockResolvedValueOnce({ error: new Error('offline'), data: undefined })
      .mockResolvedValueOnce({ data: { ...base, version: 9 } });
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      refetch,
    } as unknown as ReturnType<typeof useTransaction>);
    jest.mocked(useCategories).mockReturnValue({
      ...success(categories),
      refetch: jest.fn(async () => ({ data: categories })),
    } as unknown as ReturnType<typeof useCategories>);
    setCategory.mockRejectedValueOnce(
      new ApiError('conflict', 412, {
        code: 'VERSION_CONFLICT',
        status: 412,
        title: 'Concurrent edit',
      }),
    );
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(screen.getByText('This item was changed meanwhile')).toBeTruthy());
    await fireEvent.press(screen.getByRole('button', { name: 'Review and save again' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry review' })).toBeTruthy());
    await fireEvent.press(screen.getByRole('button', { name: 'Save again' }));
    expect(setCategory).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByRole('button', { name: 'Retry review' }));
    await waitFor(() => expect(refetch).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Retry review' })).toBeNull());
    await fireEvent.press(screen.getByRole('button', { name: 'Save again' }));
    await waitFor(() =>
      expect(setCategory).toHaveBeenNthCalledWith(2, { categoryId: 'dining', version: 9 }),
    );
  });

  it('keeps deactivated category labels in history without offering them for edits', async () => {
    const deactivated = categories.map((item) =>
      item.id === 'groceries' ? { ...item, active: false } : item,
    );
    jest
      .mocked(useCategories)
      .mockImplementation(
        (includeInactive = false) =>
          success(
            includeInactive ? deactivated : deactivated.filter((item) => item.active),
          ) as unknown as ReturnType<typeof useCategories>,
      );
    jest.mocked(useTransactionHistory).mockReturnValue(
      success({
        pages: [
          {
            content: [
              {
                occurredAt: '2026-11-09T10:00:00Z',
                kind: 'CATEGORY_CHANGED',
                fromCategoryId: 'groceries',
                toCategoryId: 'dining',
                actorDisplayName: null,
                reason: null,
              },
            ],
            page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
          },
        ],
      }) as unknown as ReturnType<typeof useTransactionHistory>,
    );
    await show();
    expect(screen.getByText('Category: Food › Groceries → Food › Dining out')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    expect(screen.queryByRole('radio', { name: 'Food › Groceries' })).toBeNull();
  });

  it('opens a related corrected transaction from paginated history and loads more', async () => {
    const more = jest.fn();
    const onRelated = jest.fn();
    jest.mocked(useTransactionHistory).mockReturnValue({
      ...success({
        pages: [
          {
            content: [
              {
                kind: 'CORRECTED',
                occurredAt: '2026-11-09T10:00:00Z',
                relatedTransactionId: 'previous-transaction',
                actorDisplayName: null,
                fromCategoryId: null,
                toCategoryId: null,
                reason: null,
              },
            ],
            page: { number: 0, size: 50, totalElements: 51, totalPages: 2 },
          },
        ],
      }),
      hasNextPage: true,
      fetchNextPage: more,
    } as unknown as ReturnType<typeof useTransactionHistory>);
    await render(
      <QueryClientProvider client={new QueryClient()}>
        <IntlProvider locale="en" messages={en}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
            onRelatedTransaction={onRelated}
          />
        </IntlProvider>
      </QueryClientProvider>,
    );
    expect(screen.getByText('Corrected automatically')).toBeTruthy();
    await fireEvent.press(screen.getByRole('link', { name: 'Open the previous transaction' }));
    expect(onRelated).toHaveBeenCalledWith('previous-transaction');
    await fireEvent.press(screen.getByRole('button', { name: 'Load more history' }));
    expect(more).toHaveBeenCalledTimes(1);
  });

  it('allows cancelling the picker without submitting', async () => {
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    if (Platform.OS === 'web') {
      await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('radio', { name: 'Food › Dining out' })).toBeNull();
    } else {
      // The bottom-sheet close action belongs to the shared Dialog.
      await fireEvent.press(screen.getByRole('button', { name: 'Close' }));
    }
    expect(setCategory).not.toHaveBeenCalled();
  });

  it('keeps the category sheet visible when dismissal is attempted during a pending save', async () => {
    let rejectSave: ((error: Error) => void) | undefined;
    setCategory.mockImplementationOnce(
      () =>
        new Promise<Transaction>((_resolve, reject) => {
          rejectSave = reject;
        }),
    );
    await render(
      <QueryClientProvider client={new QueryClient()}>
        <IntlProvider locale="en" messages={en}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
          />
        </IntlProvider>
      </QueryClientProvider>,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(setCategory).toHaveBeenCalledTimes(1));
    if (Platform.OS === 'web') {
      await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    } else {
      await fireEvent.press(screen.getByRole('button', { name: 'Close' }));
      const modal = screen.getByTestId('shared-dialog-modal');
      await fireEvent(modal, 'requestClose');
    }
    expect(screen.getByRole('radio', { name: 'Food › Dining out' })).toBeTruthy();
    await act(async () => rejectSave?.(new Error('offline')));
    expect(await screen.findByText(/Couldn't save the category/)).toBeTruthy();
    expect(screen.getByText('✓ Food › Dining out')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(setCategory).toHaveBeenCalledTimes(2));
  });

  it('shows back-to-account recovery and hides cached details if a category write returns NOT_FOUND', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('not found', 404, {
        code: 'NOT_FOUND',
        status: 404,
        title: 'Missing transaction',
      }),
    );
    const onBack = await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    expect(
      await screen.findByText('This transaction no longer exists or is unavailable to you'),
    ).toBeTruthy();
    expect(screen.queryByText('Migros')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Back to account' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('hides a cached transaction after its background read reports 404', async () => {
    const client = new QueryClient();
    const renderScreen = () => (
      <QueryClientProvider client={client}>
        <IntlProvider locale="en" messages={en}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
          />
        </IntlProvider>
      </QueryClientProvider>
    );
    const view = await render(renderScreen());
    expect(screen.getByText('Migros')).toBeTruthy();
    const failure = new ApiError('not found', 404, {
      code: 'NOT_FOUND',
      status: 404,
      title: 'Missing transaction',
    });
    jest.mocked(useTransaction).mockReturnValue({
      ...success(base),
      error: failure,
      isError: true,
    } as unknown as ReturnType<typeof useTransaction>);
    await view.rerender(renderScreen());
    expect(screen.queryByText('Migros')).toBeNull();
    expect(
      screen.getByText('This transaction no longer exists or is unavailable to you'),
    ).toBeTruthy();
  });

  it('marks an account archived if the server rejects a category change', async () => {
    setCategory.mockRejectedValueOnce(
      new ApiError('archived', 409, {
        code: 'ACCOUNT_ARCHIVED',
        status: 409,
        title: 'Account archived',
      }),
    );
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    expect(await screen.findByText('Restore the account to change its transactions.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Change category' })).toBeNull();
    expect(setCategory).toHaveBeenCalledTimes(1);
  });

  it('disables Save again and explains when the conflict review finds a voided transaction', async () => {
    jest.mocked(useConflict).mockImplementation(useMockConflict);
    const voided = { ...base, version: 8, voidedAt: '2026-11-09T10:00:00Z' };
    jest.mocked(useTransaction).mockImplementation(() => {
      const [transaction, setTransaction] = useState<Transaction>(base);
      return {
        ...success(transaction),
        refetch: jest.fn(async () => {
          setTransaction(voided);
          return { data: voided };
        }),
      } as unknown as ReturnType<typeof useTransaction>;
    });
    jest.mocked(useCategories).mockReturnValue({
      ...success(categories),
      refetch: jest.fn(async () => ({ data: categories })),
    } as unknown as ReturnType<typeof useCategories>);
    setCategory.mockRejectedValueOnce(
      new ApiError('conflict', 412, { code: 'VERSION_CONFLICT', status: 412, title: 'Conflict' }),
    );
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await fireEvent.press(await screen.findByRole('button', { name: 'Review and save again' }));
    expect(
      await screen.findByText(
        'This transaction was removed. Its category can no longer be changed.',
      ),
    ).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Save again' }));
    expect(setCategory).toHaveBeenCalledTimes(1);
  });

  it.each(['BUY', 'SELL', 'DIVIDEND'])(
    'shows a retryable security-name error while retaining trade details for %s',
    async (transactionType) => {
      fixtures({
        ...base,
        transactionType,
        securityId: 'security-1',
        categoryId: null,
        quantity: '10',
        grossAmount: '100.00',
      });
      const refetch = jest.fn();
      jest.mocked(useSecurity).mockReturnValue({
        ...success(undefined),
        isError: true,
        error: new Error('security unavailable'),
        refetch,
      } as unknown as ReturnType<typeof useSecurity>);
      const client = new QueryClient();
      const renderScreen = () => (
        <QueryClientProvider client={client}>
          <IntlProvider locale="en" messages={en}>
            <TransactionDetail
              accountId="account-1"
              transactionId="transaction-1"
              onBack={jest.fn()}
            />
          </IntlProvider>
        </QueryClientProvider>
      );
      const view = await render(renderScreen());
      expect(screen.getByText('Security')).toBeTruthy();
      expect(screen.getByText('Something went wrong.')).toBeTruthy();
      expect(screen.getByText('Gross amount')).toBeTruthy();
      await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
      expect(refetch).toHaveBeenCalledTimes(1);
      jest
        .mocked(useSecurity)
        .mockReturnValue(
          success({ displayName: 'Test Security' }) as unknown as ReturnType<typeof useSecurity>,
        );
      await view.rerender(renderScreen());
      expect(screen.getByText('Test Security')).toBeTruthy();
      expect(screen.getByText('Gross amount')).toBeTruthy();
    },
  );

  it('uses informal German for category errors and pending review copy', async () => {
    const client = new QueryClient();
    await render(
      <QueryClientProvider client={client}>
        <IntlProvider locale="de-CH" messages={de}>
          <TransactionDetail
            accountId="account-1"
            transactionId="transaction-1"
            onBack={jest.fn()}
          />
        </IntlProvider>
      </QueryClientProvider>,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Kategorie ändern' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Essen › Restaurant' }));
    setCategory.mockRejectedValueOnce(new Error('offline'));
    await fireEvent.press(screen.getByRole('button', { name: 'Kategorie speichern' }));
    expect(await screen.findByText(/Prüfe deine Verbindung/)).toBeTruthy();
    expect(screen.queryByText(/Prüfen Sie/)).toBeNull();
  });

  it('refreshes active category choices following a validation rejection', async () => {
    const refetch = jest.fn(async () => ({ data: categories.filter((c) => c.id !== 'dining') }));
    jest.mocked(useCategories).mockReturnValue({
      ...success(categories),
      refetch,
    } as unknown as ReturnType<typeof useCategories>);
    setCategory.mockRejectedValueOnce(
      new ApiError('invalid category', 422, {
        code: 'VALIDATION_FAILED',
        status: 422,
        title: 'Validation failed',
      }),
    );
    await show();
    await fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Food › Dining out' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
    expect(screen.getByText("This category can't be used. Pick another one.")).toBeTruthy();
    expect(setCategory).toHaveBeenCalledTimes(1);
  });
});
