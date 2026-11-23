import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { within } from '@testing-library/react-native';
import { fireEvent, renderRouter, screen, waitFor } from 'expo-router/testing-library';
import { useEffect } from 'react';
import { Platform, Pressable, Text, View, useWindowDimensions } from 'react-native';

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

const accountRouteMounted = jest.fn();

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
  const read = async (
    path: string,
    options?: { params?: { accountId?: string }; query?: { size?: number } },
  ) => {
    const { params, query } = options ?? {};
    switch (path) {
      case '/api/v1/accounts/{accountId}':
        return createdAccount({
          id: params?.accountId,
          name: params?.accountId === 'loan' ? 'Home loan' : 'Paying account',
          hasTransactions: false,
          nature: params?.accountId === 'loan' ? 'LIABILITY' : 'ASSET',
          hasAmortisation: params?.accountId === 'loan',
        });
      case '/api/v1/accounts/{accountId}/repayments': {
        const size = query?.size ?? 3;
        return collectionPage(repayments.slice(0, size), 0, size, repayments.length);
      }
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
