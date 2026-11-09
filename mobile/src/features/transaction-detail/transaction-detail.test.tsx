import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
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
import en from '@/i18n/en.json';
import { TransactionDetail } from './transaction-detail';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/api/queries/conflict', () => ({ useConflict: jest.fn() }));
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

function show() {
  const onBack = jest.fn();
  render(
    <IntlProvider locale="en" messages={en}>
      <TransactionDetail accountId="account-1" transactionId="transaction-1" onBack={onBack} />
    </IntlProvider>,
  );
  return onBack;
}

describe('S4 transaction detail', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.mocked(useAmountsHidden).mockReturnValue(false);
    fixtures();
  });

  it('shows automatic category provenance and current server values', () => {
    show();
    expect(screen.getByText('Migros')).toBeTruthy();
    expect(screen.getByText('Food › Groceries · automatic')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Reset category' })).toBeNull();
    expect(screen.getByText('Bank · Current account')).toBeTruthy();
  });

  it('sends the server ETag version with a category override', async () => {
    show();
    fireEvent.press(screen.getByRole('button', { name: 'Change category' }));
    fireEvent.press(screen.getByRole('button', { name: 'Food › Dining out' }));
    await waitFor(() =>
      expect(setCategory).toHaveBeenCalledWith({ categoryId: 'dining', version: 7 }),
    );
  });

  it('shows reset only for user overrides, and sends the original ETag version', async () => {
    fixtures({ ...base, categoryAssignedBy: 'USER' });
    show();
    expect(screen.getByText('Food › Groceries · by you')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Reset category' }));
    await waitFor(() => expect(resetCategory).toHaveBeenCalledWith({ version: 7 }));
  });

  it('requires the VOID explanation before deleting an imported row', async () => {
    const back = show();
    fireEvent.press(screen.getByRole('button', { name: 'Delete transaction' }));
    expect(screen.getByText(/Void this imported transaction/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Confirm deletion' }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith({ version: 7 }));
    expect(back).toHaveBeenCalled();
  });

  it('hides delete for nonremovable or reconciliation rows', () => {
    fixtures({ ...base, removal: null, reconciliationAdjustment: 'ADJUSTMENT' });
    show();
    expect(screen.queryByRole('button', { name: 'Delete transaction' })).toBeNull();
    expect(screen.getByText('Change this through reconciliation.')).toBeTruthy();
  });

  it('renders dividend trade values without a category picker', () => {
    fixtures({
      ...base,
      transactionType: 'DIVIDEND',
      categoryId: null,
      securityId: 'security-1',
      grossAmount: '100.00',
      taxWithheldAmount: '15.00',
      netAmount: '85.00',
    });
    show();
    expect(screen.queryByRole('button', { name: 'Change category' })).toBeNull();
    expect(screen.getByText('Gross amount')).toBeTruthy();
    expect(screen.getByText('Tax withheld')).toBeTruthy();
    expect(screen.getByText('Net amount')).toBeTruthy();
  });
});
