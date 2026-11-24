import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { Platform } from 'react-native';

import { collectionPage } from '@/api/collection-page.fixture';
import {
  POSITION_SLOT_TIMEOUT_MS,
  accountPositionsQuery,
  resetPositionRequests,
} from '@/api/queries/resources';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';
import type { NetWorth } from '@/features/overview/overview-model';
import type {
  PortfolioAccount,
  PortfolioView,
  Position,
  PositionsResponse,
} from '@/features/portfolio-view/model';
import { shownGain } from '@/features/portfolio-view/position-gain';
import { setAmountsHidden } from '@/features/privacy/amount-visibility';
import { withIntl } from '@/i18n/test-utils';

import { InvestmentsScreen } from './investments-screen';
import { investmentDepots, investmentFigures, orderPositions, visiblePositionSort } from './model';

jest.mock('@/global.css', () => ({}));
jest.mock('@/api/typed-api', () => ({ typedApi: { get: jest.fn() } }));
jest.mock('expo-router', () => ({ useRouter: jest.fn() }));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));

const mockWindow = { width: 390, height: 844, scale: 1, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));

const originalPlatform = Platform.OS;
afterEach(() => {
  Platform.OS = originalPlatform;
  jest.mocked(typedApi.get).mockReset();
  jest.useRealTimers();
  mockWindow.width = 390;
  resetPositionRequests();
});

const asOf = '2026-11-21';
const depot: PortfolioAccount = {
  accountId: 'depot-1',
  name: 'Euro Depot',
  accountType: 'SECURITIES',
  currency: 'EUR',
  nature: 'ASSET',
  balance: '910.52',
  valueInPortfolioCurrency: '952.52',
  valueKnown: true,
  valueStatus: 'STALE',
  valueSourceDate: asOf,
  positionsCount: 2,
  positionsAsOf: asOf,
  warnings: [],
  totalReturn: null,
  totalReturnPercent: null,
  missingCostBasisCount: 1,
  foreignCurrencyCostBasisCount: null,
  dueDate: null,
  linkedPropertyAccountId: null,
  maskedIdentifier: null,
  reconciliationStatus: 'NOT_APPLICABLE',
  statementDate: null,
};
const secondDepot: PortfolioAccount = {
  ...depot,
  accountId: 'depot-2',
  name: 'Swiss Depot',
  currency: 'CHF',
  balance: '909.97',
  totalReturn: '17.51',
  totalReturnPercent: null,
  missingCostBasisCount: null,
};
const view: PortfolioView = {
  portfolioId: 'portfolio-1',
  name: 'Test Portfolio',
  portfolioType: 'MIXED',
  portfolioTypeOverridden: false,
  personalAssetsDefault: false,
  portfolioCurrency: 'CHF',
  workspaceCurrency: 'CHF',
  asOf,
  total: '1938.99',
  totalInWorkspaceCurrency: '1938.99',
  complete: true,
  valueStatus: 'CURRENT',
  warnings: [],
  sections: [
    {
      section: 'DEPOT',
      subtotal: '1987.19',
      subtotalInWorkspaceCurrency: '1987.19',
      complete: true,
      valueStatus: 'CURRENT',
      accounts: [depot, secondDepot],
    },
  ],
  institutionName: null,
  logoUrl: null,
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  lastImport: null,
};
const netWorth: NetWorth = {
  currency: 'CHF',
  asOf,
  netWorth: '9000.23',
  totalAssets: '9000.23',
  totalLiabilities: '0',
  complete: true,
  approximate: false,
  warnings: [],
  accounts: [],
  sections: [
    {
      section: 'DEPOT',
      subtotal: '1999.17',
      complete: true,
      valueStatus: 'ESTIMATED',
      accountCount: 2,
    },
  ],
};
const position: Position = {
  securityId: 'security-1',
  displayName: 'Example Fund',
  isin: null,
  quantity: '1.24',
  price: '100',
  priceCurrency: 'USD',
  value: '123.45',
  valueInAccountCurrency: '128.70',
  costBasis: '90.00',
  costBasisCurrency: 'USD',
  costBasisInAccountCurrency: null,
  costBasisEstimated: false,
  unrealisedGain: '12.34',
  unrealisedGainPercent: '-0.32',
  unrealisedGainCurrency: 'USD',
  unrealisedGainInAccountCurrency: null,
  unrealisedGainInAccountCurrencyPercent: null,
  valueStatus: 'STALE',
};
const noBasis: Position = {
  ...position,
  securityId: 'security-2',
  displayName: 'Fund Without Basis',
  costBasis: null,
  costBasisCurrency: null,
  unrealisedGain: '99.99', // A conflicting response must still suppress the gain.
  unrealisedGainPercent: '10.9',
};
const snapshot: PositionsResponse = {
  accountId: depot.accountId,
  currency: depot.currency,
  asOf,
  complete: false,
  source: 'HOLDINGS_SNAPSHOT',
  positions: [position, noBasis],
};

const ownBalance: components['schemas']['AccountValuation'] = {
  accountId: depot.accountId,
  name: depot.name,
  nativeCurrency: 'EUR',
  currency: 'EUR',
  nature: 'ASSET',
  value: '910.52',
  valueKnown: true,
  valueSourceDate: '2026-11-20',
  valueSourceStale: false,
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  valueBasis: 'LATEST_SNAPSHOT',
  warnings: [],
};

describe('Investments view model', () => {
  it.each(['UNKNOWN', 'STALE'] as const)(
    'uses depot-currency metadata with a %s portfolio valuation and keeps a usable fallback',
    (valueStatus) => {
      const scope = {
        account: { ...depot, valueKnown: valueStatus !== 'UNKNOWN', valueStatus },
        portfolioCurrency: 'CHF',
      };
      expect(investmentFigures(scope, undefined, snapshot, ownBalance).value).toMatchObject({
        amount: '910.52',
        currency: 'EUR',
        known: true,
        status: 'CURRENT',
        asOf: '2026-11-20',
      });
      expect(investmentFigures(scope, undefined, snapshot).value).toMatchObject({
        amount: '910.52',
        known: true,
        status: undefined,
        asOf: null,
        statusUnavailable: true,
      });
      expect(
        investmentFigures(scope, undefined, snapshot, {
          ...ownBalance,
          value: null,
          valueKnown: false,
        }).value,
      ).toMatchObject({ amount: null, known: false, status: 'UNKNOWN' });
    },
  );

  it('preserves server figures and never reconstructs gains or totals', () => {
    const depots = investmentDepots([view]);
    expect(investmentFigures(depots[0], netWorth, snapshot)).toMatchObject({
      value: { amount: '910.52', currency: 'EUR', complete: false },
      performance: { amount: null, percent: null, missingCostBasisCount: 1 },
    });
    expect(investmentFigures(depots[1], netWorth, undefined).performance).toMatchObject({
      amount: '17.51',
      percent: null,
    });
    expect(investmentFigures(undefined, netWorth, undefined)).toMatchObject({
      value: { amount: '1999.17', currency: 'CHF' },
      performance: undefined,
    });
    expect(investmentFigures(undefined, undefined, snapshot).value).toBeUndefined();
    expect(shownGain(position, 'EUR')).toEqual({
      amount: '12.34',
      percent: '-0.32',
      currency: 'USD',
    });
    expect(shownGain(noBasis, 'EUR')).toBeNull();
    // S2/S3 keep the server's account-currency gain, which can rest on its cost there alone.
    expect(
      shownGain(
        {
          ...noBasis,
          costBasisInAccountCurrency: '1000.00',
          unrealisedGainInAccountCurrency: '50.00',
        },
        'EUR',
      ),
    ).toEqual({ amount: '50.00', percent: null, currency: 'EUR' });
    expect(
      orderPositions(
        { ...snapshot, positions: [noBasis, position] },
        { key: 'gain', direction: 'descending' },
        'en',
      ),
    ).toEqual([position, noBasis]);
  });
});

describe('Investments screen', () => {
  it.each([
    ['ios', 390],
    ['web', 1280],
  ] as const)(
    'groups All depots and opens a scoped performance breakdown on %s',
    async (platform, width) => {
      Platform.OS = platform;
      mockWindow.width = width;
      await act(() => setAmountsHidden(true));
      const get = jest.mocked(typedApi.get);
      get.mockImplementation(async (path, options) => {
        let data: unknown;
        switch (path) {
          case '/api/v1/workspace':
            data = { currency: 'CHF' };
            break;
          case '/api/v1/portfolios':
            data = collectionPage([{ id: view.portfolioId, name: view.name }], 0, 1, 1);
            break;
          case '/api/v1/portfolios/{portfolioId}/view':
            data = view;
            break;
          case '/api/v1/net-worth':
            data = netWorth;
            break;
          case '/api/v1/accounts/{accountId}/balance':
            data = ownBalance;
            break;
          case '/api/v1/accounts/{accountId}':
            data = { hasTransactions: true };
            break;
          case '/api/v1/accounts/{accountId}/transactions':
            data = collectionPage(
              [
                {
                  id: 'activity-1',
                  counterpartyName: 'Dividend payer',
                  bookingDate: asOf,
                  amount: '5',
                  currency: 'EUR',
                },
              ],
              0,
              10,
              1,
            );
            break;
          case '/api/v1/accounts/{accountId}/positions':
            data =
              options?.params &&
              'accountId' in options.params &&
              options.params.accountId === depot.accountId
                ? snapshot
                : {
                    ...snapshot,
                    accountId: secondDepot.accountId,
                    currency: 'CHF',
                    positions: [{ ...position, displayName: 'Swiss Fund' }],
                  };
            break;
          default:
            throw new Error('Unexpected query in Investments test');
        }
        return data as never;
      });
      const client = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
      });
      const onScope = jest.fn();
      const onAccount = jest.fn();
      const route = (accountId?: string) => (
        <QueryClientProvider client={client}>
          {withIntl(
            <InvestmentsScreen
              accountId={accountId}
              onScope={onScope}
              onAddDepot={jest.fn()}
              onAccount={onAccount}
            />,
          )}
        </QueryClientProvider>
      );
      const tree = await render(route());
      await screen.findByText('Example Fund');
      await screen.findByText('Swiss Fund');
      expect(screen.queryByText("CHF 1'999.17")).toBeNull();
      await fireEvent.press(screen.getByRole('switch', { name: 'Hide amounts' }));
      expect(screen.getByText("CHF 1'999.17")).toBeOnTheScreen();
      expect(screen.queryByText('Total return')).toBeNull();
      expect(screen.queryByText('Allocation')).toBeNull();
      expect(screen.queryByText('Dividends & interest')).toBeNull();
      expect(get.mock.calls.some(([path]) => path.includes('/history'))).toBe(false);

      await fireEvent.press(screen.getByRole('radio', { name: depot.name }));
      expect(onScope).toHaveBeenCalledWith(depot.accountId);
      await tree.rerender(route(depot.accountId));

      expect(await screen.findByText('EUR 910.52')).toBeOnTheScreen();
      expect(screen.queryByText('Swiss Fund')).toBeNull();
      expect(screen.getByText('No cost basis')).toBeOnTheScreen();
      const row = screen.getByLabelText(/^Example Fund, /);
      expect(row.props.accessibilityLabel).toContain('Price from 21 Nov 2026');
      await fireEvent.press(screen.getByRole('switch', { name: 'Hide amounts' }));
      expect(screen.getByLabelText(/^Example Fund, /).props.accessibilityLabel).toContain('hidden');
      await fireEvent.press(screen.getByRole('switch', { name: 'Hide amounts' }));
      expect(await screen.findByText('Dividend payer')).toBeOnTheScreen();
      await fireEvent.press(screen.getByRole('button', { name: 'See all activity' }));
      expect(onAccount).toHaveBeenCalledWith(depot.accountId, 'activity');
      expect(screen.getByText('+USD 12.34')).toBeOnTheScreen();
      expect(screen.queryByText('+USD 99.99')).toBeNull();
      expect(screen.getByText('Cost basis missing for 1 position')).toBeOnTheScreen();
      expect(screen.getAllByText('Price from 21 Nov 2026').length).toBeGreaterThan(0);
      await fireEvent.press(screen.getByRole('button', { name: 'View performance breakdown' }));
      const sheet = screen.getByTestId(
        platform === 'web' ? 'shell-detail-panel' : 'shared-dialog-modal',
      );
      expect(within(sheet).getByText('Performance · Euro Depot')).toBeOnTheScreen();
      expect(within(sheet).getByText('Realised gain')).toBeOnTheScreen();
      expect(
        within(sheet).getAllByText('This figure is not available yet.').length,
      ).toBeGreaterThan(0);
    },
  );
});

function setupScreen() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } },
  });
  const onAccount = jest.fn();
  const onAddDepot = jest.fn();
  const route = (accountId?: string) => (
    <QueryClientProvider client={client}>
      {withIntl(
        <InvestmentsScreen
          accountId={accountId}
          onScope={jest.fn()}
          onAccount={onAccount}
          onAddDepot={onAddDepot}
        />,
      )}
    </QueryClientProvider>
  );
  return { route, onAccount, onAddDepot };
}

type FixtureOptions = {
  views: PortfolioView[];
  netWorth?: NetWorth;
  snapshot?: PositionsResponse;
};

function mockInvestments(options: FixtureOptions, holdSecondPortfolio = false) {
  const get = jest.mocked(typedApi.get);
  let fail = holdSecondPortfolio;
  get.mockImplementation(async (path, request) => {
    const params = request?.params;
    switch (path) {
      case '/api/v1/workspace':
        return { currency: 'CHF' } as never;
      case '/api/v1/portfolios':
        return collectionPage(
          options.views.map(({ portfolioId, name }) => ({ id: portfolioId, name })),
          0,
          50,
          options.views.length,
        ) as never;
      case '/api/v1/portfolios/{portfolioId}/view': {
        const id = params && 'portfolioId' in params ? params.portfolioId : undefined;
        if (fail && id === options.views[1]?.portfolioId) throw new Error('Portfolio unavailable');
        return options.views.find(({ portfolioId }) => portfolioId === id) as never;
      }
      case '/api/v1/net-worth':
        return (options.netWorth ?? netWorth) as never;
      case '/api/v1/accounts/{accountId}':
        return { hasTransactions: false } as never;
      case '/api/v1/accounts/{accountId}/balance':
        return ownBalance as never;
      case '/api/v1/accounts/{accountId}/positions':
        return {
          ...(options.snapshot ?? snapshot),
          accountId: params && 'accountId' in params ? params.accountId : undefined,
        } as never;
      default:
        throw new Error('Unexpected Investments request ' + path);
    }
  });
  return {
    allowSecondPortfolio: () => {
      fail = false;
    },
    get,
  };
}

const pillar = { ...secondDepot, accountId: 'pillar', name: 'Pillar 3a depot' };
const pillarSection = { ...view.sections[0], section: 'PILLAR_3A' as const, accounts: [pillar] };

it.each([false, true])(
  'labels the Depot subtotal independently of Pillar 3a (Pillar-only: %s)',
  async (onlyPillar) => {
    mockInvestments({
      views: [
        { ...view, sections: onlyPillar ? [pillarSection] : [view.sections[0], pillarSection] },
      ],
      netWorth: onlyPillar
        ? {
            ...netWorth,
            sections: [{ ...netWorth.sections[0], accountCount: 0, subtotal: '0.00' }],
          }
        : netWorth,
      snapshot: { ...snapshot, positions: [] },
    });
    const { route } = setupScreen();
    await render(route());
    expect(await screen.findByRole('radio', { name: pillar.name })).toBeOnTheScreen();
    if (onlyPillar) {
      expect(await screen.findByText(/No Depot section subtotal/)).toBeOnTheScreen();
      expect(screen.queryByText('Depot section subtotal')).toBeNull();
    } else {
      expect(await screen.findByText('Depot section subtotal')).toBeOnTheScreen();
      expect(screen.getByText(/Excludes Pillar 3a/)).toBeOnTheScreen();
    }
  },
);

it('keeps loaded depots visible and retries a failed portfolio view', async () => {
  const { allowSecondPortfolio } = mockInvestments(
    {
      views: [view, { ...view, portfolioId: 'other-portfolio', sections: [pillarSection] }],
      snapshot: { ...snapshot, positions: [] },
    },
    true,
  );
  const { route } = setupScreen();
  await render(route());
  expect(await screen.findByText(/Some depots could not be loaded/)).toBeOnTheScreen();
  expect(screen.getByRole('radio', { name: depot.name })).toBeOnTheScreen();
  expect(screen.queryByRole('radio', { name: pillar.name })).toBeNull();
  allowSecondPortfolio();
  await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByRole('radio', { name: pillar.name })).toBeOnTheScreen();
  expect(screen.queryByText(/Some depots could not be loaded/)).toBeNull();
});

it.each(['archived', 'reassigned'])(
  'omits portfolio history on a depot deep link after another account was %s',
  async () => {
    // The archived or reassigned account is absent from the current portfolio view. Its earlier
    // balance would still be in portfolio history. The visible depot is also in a different currency.
    const { get } = mockInvestments({
      views: [{ ...view, sections: [{ ...view.sections[0], accounts: [depot] }] }],
    });
    const { route } = setupScreen();
    await render(route(depot.accountId));
    expect(await screen.findByText('Example Fund')).toBeOnTheScreen();
    expect(get).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/balance',
      expect.objectContaining({
        params: { accountId: depot.accountId },
        query: { currency: 'EUR' },
      }),
    );
    expect(get.mock.calls.some(([path]) => path.includes('/history'))).toBe(false);
  },
);

it('offers a working Add depot action with no depots', async () => {
  mockInvestments({ views: [{ ...view, sections: [] }] });
  const { route, onAddDepot } = setupScreen();
  await render(route());
  expect(await screen.findByText('No investments yet')).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Add a depot' }));
  expect(onAddDepot).toHaveBeenCalledWith(view.portfolioId);
  expect(screen.queryByRole('button', { name: /Import/ })).toBeNull();
});

it('opens the depot account when the holdings snapshot is empty', async () => {
  mockInvestments({ views: [view], snapshot: { ...snapshot, asOf: null, positions: [] } });
  const { route, onAccount } = setupScreen();
  await render(route(depot.accountId));
  await fireEvent.press(await screen.findByRole('button', { name: 'Open depot account' }));
  expect(onAccount).toHaveBeenCalledWith(depot.accountId, 'positions');
  expect(screen.queryByRole('button', { name: /Import/ })).toBeNull();
});

it('explains a missing percentage independently of a known return amount', async () => {
  mockInvestments({ views: [view] });
  const { route } = setupScreen();
  await act(() => setAmountsHidden(false));
  await render(route(secondDepot.accountId));
  await fireEvent.press(await screen.findByRole('button', { name: 'View performance breakdown' }));
  const sheet = within(screen.getByTestId('shared-dialog-modal'));
  expect(sheet.getByText('+CHF 17.51')).toBeOnTheScreen();
  expect(
    sheet.getByText('The server did not provide a return percentage for this depot.'),
  ).toBeOnTheScreen();
  expect(sheet.getAllByText('–').length).toBeGreaterThan(0);
  expect(sheet.queryByText(/zero cost basis/i)).toBeNull();
});

it('shows all positions at narrow web widths and truncates long names only in native-style rows', async () => {
  Platform.OS = 'web';
  mockWindow.width = 390;
  const positions = Array.from({ length: 21 }, (_, index) => ({
    ...position,
    securityId: 'position-' + index,
    displayName: 'A very long fund name ' + index,
  }));
  mockInvestments({ views: [view], snapshot: { ...snapshot, positions } });
  const { route } = setupScreen();
  await render(route(depot.accountId));
  expect(await screen.findByText('A very long fund name 20')).toBeOnTheScreen();
  expect(screen.getByText('A very long fund name 20').props.numberOfLines).toBe(1);
  expect(screen.getByLabelText(/^A very long fund name 20, /)).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: /Show all/ })).toBeNull();
});

it('starts a newly selected depot without issuing abandoned queued positions requests', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const depots = Array.from({ length: 8 }, (_, index) => 'depot-' + index);
  const selected = depots[7];
  const starts: string[] = [];
  let active = 0;
  let maximum = 0;
  jest.mocked(typedApi.get).mockImplementation(async (_, options) => {
    const params = options?.params;
    const accountId = params && 'accountId' in params ? params.accountId : '';
    const signal = options?.signal;
    starts.push(accountId);
    active++;
    maximum = Math.max(maximum, active);
    try {
      if (accountId === selected) return snapshot as never;
      return await new Promise<never>((_, reject) => {
        signal?.addEventListener('abort', () => reject(signal.reason), { once: true });
      });
    } finally {
      active--;
    }
  });

  const requests = depots.map((id) => client.fetchQuery(accountPositionsQuery(id)));
  const previousSettled = Promise.allSettled(requests);
  await waitFor(() => expect(starts).toHaveLength(4));
  await client.cancelQueries({
    predicate: ({ queryKey }) => queryKey[1] !== selected,
  });
  await client.fetchQuery(accountPositionsQuery(selected));
  await previousSettled;
  expect(starts).toEqual([...depots.slice(0, 4), selected]);
  expect(maximum).toBe(4);
});

it('falls back to All depots for a missing depot while another portfolio view failed', async () => {
  mockInvestments(
    {
      views: [view, { ...view, portfolioId: 'other-portfolio', sections: [pillarSection] }],
    },
    true,
  );
  const { route } = setupScreen();
  await render(route('archived-depot'));
  expect(await screen.findByText(/This depot is unavailable/)).toBeOnTheScreen();
  expect(screen.getByText(/Some depots could not be loaded/)).toBeOnTheScreen();
  expect(screen.getByRole('radio', { name: 'All depots' })).toBeChecked();
  expect((await screen.findAllByText('Example Fund')).length).toBeGreaterThan(0);
});

it('resets a table-only sort to the default where the phone sort chips show', () => {
  const units = { key: 'units', direction: 'ascending' } as const;
  expect(visiblePositionSort(units, true)).toBe(units);
  expect(visiblePositionSort(units, false)).toEqual({ key: 'value', direction: 'descending' });
  expect(visiblePositionSort({ key: 'costBasis', direction: 'descending' }, false).key).toBe(
    'value',
  );
  const name = { key: 'name', direction: 'ascending' } as const;
  expect(visiblePositionSort(name, false)).toBe(name);
});

it('lets the member back out of the portfolio picker', async () => {
  mockInvestments({
    views: [
      { ...view, sections: [] },
      { ...view, portfolioId: 'other-portfolio', name: 'Second portfolio', sections: [] },
    ],
  });
  const { route, onAddDepot } = setupScreen();
  await render(route());
  await fireEvent.press(await screen.findByRole('button', { name: 'Add a depot' }));
  expect(screen.getByRole('radio', { name: 'Second portfolio' })).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('radio', { name: 'Second portfolio' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Add a depot' })).toBeOnTheScreen();
  expect(onAddDepot).not.toHaveBeenCalled();
});

it('frees a positions slot after its deadline when a request never settles', async () => {
  jest.useFakeTimers();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const starts: string[] = [];
  jest.mocked(typedApi.get).mockImplementation((_, options) => {
    const params = options?.params;
    const accountId = params && 'accountId' in params ? params.accountId : '';
    starts.push(accountId);
    // Ignores its abort signal on purpose: only the slot deadline can free it.
    return (accountId === 'late' ? Promise.resolve(snapshot) : new Promise(() => {})) as never;
  });
  const stuck = ['a', 'b', 'c', 'd'].map((id) =>
    client.fetchQuery(accountPositionsQuery(id)).catch((error: unknown) => error),
  );
  const late = client.fetchQuery(accountPositionsQuery('late'));
  await jest.advanceTimersByTimeAsync(0);
  expect(starts).toEqual(['a', 'b', 'c', 'd']);
  await jest.advanceTimersByTimeAsync(POSITION_SLOT_TIMEOUT_MS);
  await expect(late).resolves.toEqual(snapshot);
  expect(await Promise.all(stuck)).toEqual(Array(4).fill(new Error('Positions request timed out')));
});
