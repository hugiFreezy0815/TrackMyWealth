import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { queryKeys } from '@/api/queries/keys';
import { useAccount } from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import { createdAccount, problem } from '@/features/add-flow/test-fixtures';
import { syntheticRow } from '@/features/statement-import/fake-import-server';
import { StatementRow } from '@/features/statement-import/statement-row';
import { withIntl } from '@/i18n/test-utils';
import { LinkPocket, pocketChoices } from './link-pocket';

jest.mock('@/global.css', () => ({}));

jest.mock('@/api/typed-api', () => ({
  typedApi: { get: jest.fn(), put: jest.fn() },
}));

const source = createdAccount({ id: 'source', savingsPocketAccountId: 'old' });
const old = createdAccount({ id: 'old', name: 'Old savings', status: 'ARCHIVED' });
const target = createdAccount({ id: 'target', name: 'New savings' });
const alternate = createdAccount({ id: 'alternate', name: 'Other savings' });
const api = jest.mocked(typedApi);

function row(account: typeof source) {
  return {
    ...account,
    currency: account.nativeCurrency,
    archived: account.status === 'ARCHIVED',
    portfolioName: 'Demo',
    balance: null,
    workspaceCurrency: 'EUR',
    balanceInWorkspaceCurrency: null,
    valueKnown: false,
    valueStatus: 'UNKNOWN' as const,
    valueBasis: null,
    asOf: '2026-11-11',
    valueSourceDate: null,
  };
}

function Fixture() {
  const account = useAccount(source.id);
  return <LinkPocket account={account.data} disabled={false} showCurrent />;
}

async function setup(initial = source, extra: (typeof source)[] = [], ui = <Fixture />) {
  let current = initial;
  const accounts = [old, target, alternate, ...extra];
  let directoryFailure: Error | undefined;
  api.get.mockImplementation((async (
    path: string,
    options?: {
      params?: { accountId: string };
      query?: { archived?: boolean };
    },
  ) => {
    if (path.endsWith('{accountId}')) {
      return [current, ...accounts].find(({ id }) => id === options?.params?.accountId);
    }
    if (directoryFailure) throw directoryFailure;
    return [current, ...accounts]
      .filter(({ status }) =>
        options?.query?.archived ? status === 'ARCHIVED' : status === 'ACTIVE',
      )
      .map(row);
  }) as unknown as typeof typedApi.get);
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 30_000 },
      mutations: { gcTime: Infinity },
    },
  });
  await render(withIntl(<QueryClientProvider client={client}>{ui}</QueryClientProvider>));
  return {
    client,
    failDirectory: (error?: Error) => {
      directoryFailure = error;
    },
    update: (account: typeof source) => {
      current = account;
    },
  };
}

beforeEach(() => jest.resetAllMocks());

test('filters archived, foreign currency, ineligible and already linked accounts', () => {
  const inUse = createdAccount({ id: 'in-use' });
  expect(
    pocketChoices(
      source,
      [
        source,
        old,
        target,
        inUse,
        createdAccount({ id: 'owner', status: 'ARCHIVED', savingsPocketAccountId: inUse.id }),
        createdAccount({ id: 'card', hasStatementCycle: true }),
        createdAccount({ id: 'loan', hasAmortisation: true }),
        createdAccount({ id: 'foreign', nativeCurrency: 'CHF' }),
        createdAccount({ id: 'no-ledger', hasTransactions: false }),
      ].map(row),
    ),
  ).toEqual([row(target)]);
});

test('replaces an archived pocket, displays the new pocket and retains the returned version', async () => {
  const { client } = await setup();
  const updated = { ...source, savingsPocketAccountId: target.id, version: 4 };
  api.put.mockResolvedValue(updated);
  await screen.findByText('Savings pocket: Old savings');
  await fireEvent.press(await screen.findByRole('button', { name: 'Link savings pocket' }));
  expect(screen.getByText(/Previously imported moves stay in pending review/)).toBeOnTheScreen();
  await fireEvent.press(await screen.findByRole('button', { name: 'Link New savings' }));
  await screen.findByText('Savings pocket: New savings');
  expect(api.put).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/savings-pocket', {
    params: { accountId: source.id },
    version: 3,
    body: { savingsPocketAccountId: target.id },
  });
  expect(client.getQueryData(queryKeys.account(source.id))).toEqual(updated);
});

test.each([
  [422, 'SAVINGS_POCKET_UNSUITABLE', /cannot be used as a savings pocket/],
  [409, 'SAVINGS_POCKET_IN_USE', /already linked as a savings pocket/],
  [422, 'SAVINGS_POCKET_NOT_APPLICABLE', /cannot have a savings pocket/],
])('shows the localized problem for %s %s', async (status, code, message) => {
  await setup();
  api.put.mockRejectedValueOnce(problem(status, code));
  await fireEvent.press(await screen.findByRole('button', { name: 'Link savings pocket' }));
  await fireEvent.press(await screen.findByRole('button', { name: 'Link New savings' }));
  expect(await screen.findByText(message)).toBeOnTheScreen();
  await screen.findByRole('button', { name: 'Link Other savings' });
  expect(api.put).toHaveBeenCalledTimes(1);
});

test('reloads a stale version and waits for a fresh selection', async () => {
  const { update } = await setup();
  await fireEvent.press(await screen.findByRole('button', { name: 'Link savings pocket' }));
  const choice = await screen.findByRole('button', { name: 'Link New savings' });
  update({ ...source, version: 8 });
  api.put.mockRejectedValueOnce(problem(412, 'VERSION_CONFLICT'));
  await fireEvent.press(choice);
  await screen.findByText(/The account changed/);
  expect(api.put).toHaveBeenCalledTimes(1);

  api.put.mockResolvedValueOnce({ ...source, savingsPocketAccountId: alternate.id, version: 9 });
  await fireEvent.press(await screen.findByRole('button', { name: 'Link Other savings' }));
  await screen.findByText('Savings pocket: Other savings');
  expect(api.put).toHaveBeenLastCalledWith(
    '/api/v1/accounts/{accountId}/savings-pocket',
    expect.objectContaining({ version: 8 }),
  );
});

test('hides the action for another account’s pocket', async () => {
  await setup({ ...source, savingsPocketAccountId: null }, [
    createdAccount({ id: 'owner', savingsPocketAccountId: source.id }),
  ]);
  await waitFor(() => {
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/accounts',
      expect.objectContaining({ query: { archived: true } }),
    );
  });
  expect(screen.queryByRole('button', { name: 'Link savings pocket' })).toBeNull();
});

test.each([
  ['IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE', true],
  ['IMPORT_ROW_SAVINGS_POCKET_OTHER_CURRENCY', false],
])('offers recovery on the relevant import warning (%s)', async (code, canLink) => {
  const row = syntheticRow(1, 'ERROR', {
    warnings: [{ code, args: {}, message: 'Import warning' }],
  });
  await setup(
    source,
    [],
    <StatementRow row={row} disabled={false} onSwitch={jest.fn()} account={source} />,
  );
  expect(screen.getByText(/Import warning/)).toBeOnTheScreen();
  if (canLink) {
    await fireEvent.press(await screen.findByRole('button', { name: 'Link savings pocket' }));
    expect(await screen.findByRole('button', { name: 'Link New savings' })).toBeOnTheScreen();
  } else {
    expect(screen.queryByRole('button', { name: 'Link savings pocket' })).toBeNull();
  }
});

test('uses two list reads without per-account lookups when opening the picker', async () => {
  await setup();
  await screen.findByText('Savings pocket: Old savings');
  await fireEvent.press(await screen.findByRole('button', { name: 'Link savings pocket' }));
  await screen.findByRole('button', { name: 'Link New savings' });
  expect(api.get.mock.calls.map(([path]) => path).sort()).toEqual([
    '/api/v1/accounts',
    '/api/v1/accounts',
    '/api/v1/accounts/{accountId}',
    '/api/v1/accounts/{accountId}',
  ]);
});

test('keeps the current pocket visible when the directory fails and allows retry', async () => {
  const { client, failDirectory } = await setup();
  await screen.findByRole('button', { name: 'Link savings pocket' });
  failDirectory(new Error('Directory unavailable'));
  await client.invalidateQueries({
    queryKey: [...queryKeys.accounts(), 'savings-pocket-directory'],
  });
  const retry = await screen.findByRole('button', { name: 'Try again' });
  expect(screen.getByText('Savings pocket: Old savings')).toBeOnTheScreen();
  failDirectory();
  await fireEvent.press(retry);
  await screen.findByRole('button', { name: 'Link savings pocket' });
});
