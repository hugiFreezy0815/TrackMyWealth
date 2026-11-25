import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { within } from '@testing-library/react-native';
import { router } from 'expo-router';
import { act, fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';
import { useEffect } from 'react';
import { Platform, Pressable, Text, View, useWindowDimensions } from 'react-native';

import { ApiError } from '@/api/client';
import { collectionPage } from '@/api/collection-page.fixture';
import { typedApi } from '@/api/typed-api';
import AccountDetailRoute from '@/app/accounts/[accountId]';
import TransactionDetailRoute from '@/app/accounts/[accountId]/transactions/[transactionId]';
import { createdAccount } from '@/features/add-flow/test-fixtures';
import { AppShell } from '@/features/shell/app-shell';
import type { Repayment } from '@/features/liability-detail/queries';
import type { TransactionDetailProps } from '@/features/transaction-detail/transaction-detail';
import { withIntl } from '@/i18n/test-utils';

import type { Transaction } from './model';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-clipboard', () => ({ setStringAsync: jest.fn() }));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(async () => null) }));
jest.mock('@/features/privacy/screen-protection', () => ({
  useAppSwitcherProtection: jest.fn(),
}));
jest.mock('@/features/auth/use-identity', () => ({ useIdentity: () => null }));
jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn() } }));
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: jest.fn(),
}));
function MockTransactionDetail({ accountId, transactionId, onBack }: TransactionDetailProps) {
  return (
    <View>
      <Text>{`${accountId}/${transactionId}`}</Text>
      <Pressable accessibilityRole="button" onPress={onBack}>
        <Text>Back from transaction</Text>
      </Pressable>
    </View>
  );
}
jest.mock('@/features/transaction-detail/transaction-detail', () => ({
  TransactionDetail: (props: TransactionDetailProps) => <MockTransactionDetail {...props} />,
}));

const loanPath = '/accounts/loan';
const loanTransactionPath = '/accounts/loan/transactions/loan-payment';
const payingTransactionPath = '/accounts/payer/transactions/paying-payment';
const originalOS = Platform.OS;

const transaction = {
  id: 'paying-payment',
  accountId: 'payer',
  amount: '-800.00',
  currency: 'EUR',
  transactionType: 'DEBT_REPAYMENT',
} as Transaction;

const repayments: Repayment[] = Array.from({ length: 4 }, (_, index) => ({
  id: `repayment-${index}`,
  loanAccountId: 'loan',
  payingAccountId: 'payer',
  bookingDate: `2026-10-0${4 - index}`,
  amount: '800.00',
  principalAmount: '700.00',
  interestAmount: '100.00',
  currency: 'EUR',
  splitSource: 'STATED',
  splitEstimated: false,
  amortisationScheduleEntryId: null,
  externalId: null,
  transactions: index === 3 ? [transaction] : [],
  createdAt: '2026-10-01T00:00:00Z',
}));

const loanActivity = ['loan-payment', 'loan-fee'].map(
  (id, index) =>
    ({
      ...transaction,
      id,
      accountId: 'loan',
      bookingDate: `2026-09-0${index + 1}`,
      transactionType: 'FEE',
      merchantDescription: id === 'loan-fee' ? 'Loan fee' : 'Loan payment',
      counterpartyName: null,
      categoryId: null,
      internalTransfer: false,
      voidedAt: null,
      replacesTransactionId: null,
      correctsTransactionId: null,
      reconciliationAdjustment: null,
      amountInAccountCurrency: null,
      fxRateEstimated: false,
      warnings: [],
      version: 0,
    }) as Transaction,
);

const accountRouteMounted = jest.fn();
let historyDenied = false;
let accessRevoked = false;
const forbidden = () =>
  new ApiError('forbidden', 403, { status: 403, title: 'Forbidden', code: 'FORBIDDEN' });

/** Minimal DOM stand-in for row focus: jest-expo runs without one. */
const fakeDocument = {
  documentElement: { lang: '' },
  activeElement: null as unknown,
  focusedId: null as string | null,
  getElementById(id: string) {
    const node = {
      focus: () => {
        fakeDocument.activeElement = node;
        fakeDocument.focusedId = id;
      },
    };
    return node;
  },
};

function AccountRouteWithMountProbe() {
  useEffect(() => {
    accountRouteMounted();
  }, []);
  return <AccountDetailRoute />;
}

function renderRoute(
  initialUrl: string,
  os: typeof Platform.OS,
  width = os === 'web' ? 1280 : 390,
) {
  Platform.OS = os;
  jest.mocked(useWindowDimensions).mockReturnValue({
    width,
    height: 800,
    scale: 1,
    fontScale: 1,
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return renderRouter(
    {
      _layout: () =>
        withIntl(
          <QueryClientProvider client={client}>
            <AppShell identity={null} />
          </QueryClientProvider>,
        ),
      'accounts/[accountId]/index': AccountRouteWithMountProbe,
      'accounts/[accountId]/transactions/[transactionId]': TransactionDetailRoute,
      overview: () => null,
    },
    { initialUrl },
  );
}

beforeEach(() => {
  accountRouteMounted.mockClear();
  historyDenied = false;
  accessRevoked = false;
  fakeDocument.activeElement = null;
  fakeDocument.focusedId = null;
  const read = async (
    path: string,
    options?: { params?: { accountId?: string }; query?: { size?: number } },
  ) => {
    const { params, query } = options ?? {};
    switch (path) {
      case '/api/v1/accounts/{accountId}':
        if (params?.accountId === 'gone') throw new ApiError('not found', 404);
        return createdAccount({
          id: params?.accountId,
          name: params?.accountId === 'loan' ? 'Home loan' : 'Paying account',
          hasTransactions: params?.accountId === 'loan',
          nature: params?.accountId === 'loan' ? 'LIABILITY' : 'ASSET',
          hasAmortisation: params?.accountId === 'loan',
        });
      case '/api/v1/accounts/{accountId}/repayments': {
        const size = query?.size ?? 3;
        if (accessRevoked || (historyDenied && size > 3)) throw forbidden();
        return collectionPage(repayments.slice(0, size), 0, size, repayments.length);
      }
      case '/api/v1/accounts/{accountId}/repayments/yearly-totals':
        if (accessRevoked) throw forbidden();
        return {
          currency: 'EUR',
          years: [
            {
              year: new Date().getFullYear(),
              amountPaid: '800.00',
              interestPaid: '100.00',
              principalPaid: '700.00',
              unknownSplitAmount: '0.00',
              paymentCount: 1,
              splitEstimated: false,
            },
          ],
        };
      case '/api/v1/accounts/{accountId}/transactions':
        return params?.accountId === 'loan'
          ? collectionPage(loanActivity, 0, 50, loanActivity.length)
          : collectionPage([]);
      case '/api/v1/portfolios/{portfolioId}/view':
        return { sections: [] };
      default:
        return collectionPage([]);
    }
  };
  jest.mocked(typedApi.get).mockImplementation(read as never);
});

afterEach(() => {
  Platform.OS = originalOS;
  delete (globalThis as { document?: unknown }).document;
});

async function openPayingTransaction(container: ReturnType<typeof within> = screen) {
  await fireEvent.press(await container.findByRole('button', { name: 'See all' }));
  await fireEvent.press(
    await container.findByRole('button', { name: 'Transactions for 1 Oct 2026' }),
  );
  await fireEvent.press(container.getByRole('button', { name: 'Debt repayment' }));
  expect(await screen.findByText('payer/paying-payment')).toBeTruthy();
}

it.each([loanPath, loanTransactionPath])(
  'keeps the loan panels and linked repayment history in the desktop side column from %s',
  async (initialUrl) => {
    const view = await renderRoute(initialUrl, 'web');
    const panel = within(await screen.findByTestId('shell-detail-panel'));
    expect(await panel.findByText('Next payment')).toBeTruthy();
    expect(within(screen.getByTestId('account-detail')).queryByText('Next payment')).toBeNull();

    await openPayingTransaction(panel);
    expect(view.getPathname()).toBe(initialUrl);
    await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));
    expect(panel.queryByText('payer/paying-payment')).toBeNull();
    expect(panel.getByRole('button', { name: 'Show recent repayments' })).toBeTruthy();
    expect(panel.getByRole('button', { name: 'Debt repayment' })).toBeTruthy();
    if (initialUrl === loanTransactionPath) {
      expect(panel.getByText('loan/loan-payment')).toBeTruthy();
    }
  },
);

it.each(['ios', 'web'] as const)(
  'restores the expanded loan history after Back at phone width on %s',
  async (os) => {
    const view = await renderRoute(loanPath, os, 390);

    await openPayingTransaction();
    expect(view.getPathname()).toBe(payingTransactionPath);
    expect(accountRouteMounted).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('account-detail')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));

    await waitFor(() => expect(view.getPathname()).toBe(loanPath));
    expect(await screen.findByRole('button', { name: 'Show recent repayments' })).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Debt repayment' })).toBeTruthy();
    expect(accountRouteMounted).toHaveBeenCalledTimes(2);
  },
);

it('uses the paying account when a native transaction link has no repayment origin', async () => {
  const view = await renderRoute(payingTransactionPath, 'ios');
  await fireEvent.press(await screen.findByRole('button', { name: 'Back from transaction' }));
  await waitFor(() => expect(view.getPathname()).toBe('/accounts/payer'));
});

it('shows the paying transaction on desktop when the repayment origin cannot be read', async () => {
  await renderRoute(`${payingTransactionPath}?repaymentFromAccountId=gone`, 'web');
  expect(await screen.findByText(/This account no longer exists/)).toBeTruthy();
  const panel = within(screen.getByTestId('shell-detail-panel'));
  expect(panel.getByText('payer/paying-payment')).toBeTruthy();
});

it('opens a loan activity row through the URL on the desktop transaction route', async () => {
  const view = await renderRoute(loanTransactionPath, 'web');
  const panel = within(await screen.findByTestId('shell-detail-panel'));
  await openPayingTransaction(panel);

  await fireEvent.press(await within(screen.getByTestId('account-detail')).findByText('Loan fee'));
  await waitFor(() => expect(view.getPathname()).toBe('/accounts/loan/transactions/loan-fee'));
  expect(await screen.findByText('loan/loan-fee')).toBeTruthy();
  expect(screen.queryByText('payer/paying-payment')).toBeNull();
  await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));
  await waitFor(() => expect(view.getPathname()).toBe(loanPath));
  expect(screen.queryByText(/^loan\/loan-/)).toBeNull();
});

it('opens the loan with recent repayments when it is reached other than by Back', async () => {
  const view = await renderRoute(loanPath, 'ios', 390);
  await openPayingTransaction();

  await act(() => router.push('/overview'));
  await waitFor(() => expect(view.getPathname()).toBe('/overview'));
  await act(() => router.push(loanPath));
  await waitFor(() => expect(view.getPathname()).toBe(loanPath));
  expect(await screen.findByRole('button', { name: 'See all' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Show recent repayments' })).toBeNull();
});

it('shows the recent repayments again after the full history was denied', async () => {
  historyDenied = true;
  await renderRoute(loanPath, 'ios', 390);
  await fireEvent.press(await screen.findByRole('button', { name: 'See all' }));
  expect(await screen.findByText('You are not allowed to do this.')).toBeTruthy();

  await fireEvent.press(screen.getByRole('button', { name: 'Show recent repayments' }));
  expect(await screen.findByRole('button', { name: 'See all' })).toBeTruthy();
  expect(screen.queryByText('You are not allowed to do this.')).toBeNull();

  historyDenied = false;
  await fireEvent.press(screen.getByRole('button', { name: 'See all' }));
  expect(await screen.findByRole('button', { name: 'Transactions for 1 Oct 2026' })).toBeTruthy();
});

it('does not restore a desktop side-column view on a later mount', async () => {
  const view = await renderRoute(loanPath, 'web');
  const panel = within(await screen.findByTestId('shell-detail-panel'));
  await openPayingTransaction(panel);
  await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));
  await fireEvent.press(panel.getByRole('button', { name: 'Show recent repayments' }));

  await act(() => router.push(loanTransactionPath));
  await waitFor(() => expect(view.getPathname()).toBe(loanTransactionPath));
  expect(await screen.findByText('loan/loan-payment')).toBeTruthy();
  const panels = screen.getAllByTestId('shell-detail-panel');
  const latest = within(panels[panels.length - 1]);
  expect(await latest.findByRole('button', { name: 'See all' })).toBeTruthy();
  expect(latest.queryByRole('button', { name: 'Show recent repayments' })).toBeNull();
});

it('opens a loan activity row through the URL on the desktop repayment link route', async () => {
  const view = await renderRoute(`${payingTransactionPath}?repaymentFromAccountId=loan`, 'web');
  expect(await screen.findByText('payer/paying-payment')).toBeTruthy();

  const detail = within(await screen.findByTestId('account-detail'));
  await fireEvent.press(await detail.findByText('Loan fee'));
  await waitFor(() => expect(view.getPathname()).toBe('/accounts/loan/transactions/loan-fee'));
  expect(view.getSearchParams()).toHaveProperty('repaymentFromAccountId', 'loan');
  expect(await screen.findByText('loan/loan-fee')).toBeTruthy();
  await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));
  await waitFor(() => expect(view.getPathname()).toBe(loanPath));
  expect(screen.queryByText('payer/paying-payment')).toBeNull();
});

it('loads the recent repayments again after access to the full history was denied', async () => {
  await renderRoute(loanPath, 'ios', 390);
  expect(await screen.findByRole('button', { name: 'See all' })).toBeTruthy();
  expect(await screen.findByText('4 Oct 2026')).toBeTruthy();
  expect(screen.queryByLabelText(/^Interest paid in \d{4}: not available$/)).toBeNull();

  accessRevoked = true;
  await fireEvent.press(screen.getByRole('button', { name: 'See all' }));
  expect(await screen.findByText('You are not allowed to do this.')).toBeTruthy();
  await fireEvent.press(screen.getByRole('button', { name: 'Show recent repayments' }));

  expect(await screen.findByLabelText(/^Interest paid in \d{4}: not available$/)).toBeTruthy();
  expect(await screen.findByText('You are not allowed to do this.')).toBeTruthy();
  expect(screen.queryByText('4 Oct 2026')).toBeNull();
});

it('keeps the side-column history when a loan row opens on the desktop repayment link route', async () => {
  const view = await renderRoute(`${payingTransactionPath}?repaymentFromAccountId=loan`, 'web');
  const panel = within(await screen.findByTestId('shell-detail-panel'));
  await fireEvent.press(await panel.findByRole('button', { name: 'See all' }));
  await fireEvent.press(await panel.findByRole('button', { name: 'Transactions for 1 Oct 2026' }));

  const detail = within(screen.getByTestId('account-detail'));
  await fireEvent.press(await detail.findByText('Loan fee'));
  await waitFor(() => expect(view.getPathname()).toBe('/accounts/loan/transactions/loan-fee'));
  expect(await screen.findByText('loan/loan-fee')).toBeTruthy();
  const panels = screen.getAllByTestId('shell-detail-panel');
  const latest = within(panels[panels.length - 1]);
  expect(latest.getByRole('button', { name: 'Show recent repayments' })).toBeTruthy();
  expect(latest.getByRole('button', { name: 'Debt repayment' })).toBeTruthy();
});

it('dismisses to the loan below after a loan row replaced a repayment link', async () => {
  const view = await renderRoute(loanPath, 'web');
  await screen.findByTestId('account-detail');
  await act(() => router.push(`${payingTransactionPath}?repaymentFromAccountId=loan`));
  expect(await screen.findByText('payer/paying-payment')).toBeTruthy();

  const details = screen.getAllByTestId('account-detail');
  await fireEvent.press(await within(details[details.length - 1]).findByText('Loan fee'));
  await waitFor(() => expect(view.getPathname()).toBe('/accounts/loan/transactions/loan-fee'));
  await fireEvent.press(await screen.findByRole('button', { name: 'Back from transaction' }));
  await waitFor(() => expect(view.getPathname()).toBe(loanPath));
  expect(router.canGoBack()).toBe(false);
});

it('returns focus to the loan row after the desktop repayment link route closes it', async () => {
  Object.defineProperty(globalThis, 'document', { value: fakeDocument, configurable: true });
  const view = await renderRoute(`${payingTransactionPath}?repaymentFromAccountId=loan`, 'web');
  const detail = within(await screen.findByTestId('account-detail'));
  await fireEvent.press(await detail.findByText('Loan fee'));
  expect(await screen.findByText('loan/loan-fee')).toBeTruthy();

  await fireEvent.press(screen.getByRole('button', { name: 'Back from transaction' }));
  await waitFor(() => expect(view.getPathname()).toBe(loanPath));
  await waitFor(() => expect(fakeDocument.focusedId).toBe('transaction-loan-fee'));
});
