import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { Platform } from 'react-native';

import { collectionPage } from '@/api/collection-page.fixture';
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
import { investmentDepots, investmentFigures, orderPositions } from './model';

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
  totalReturnPercent: '-0.16',
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

describe('Investments view model', () => {
  it('preserves server figures and never reconstructs gains or totals', () => {
    const depots = investmentDepots([view]);
    expect(depots.every(({ historyPortfolioId }) => historyPortfolioId === undefined)).toBe(true);
    expect(investmentFigures(depots[0], netWorth, snapshot)).toMatchObject({
      value: { amount: '910.52', currency: 'EUR', complete: false },
      performance: { amount: null, percent: null, missingCostBasisCount: 1 },
    });
    expect(investmentFigures(depots[1], netWorth, undefined).performance).toMatchObject({
      amount: '17.51',
      percent: '-0.16',
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
    expect(orderPositions({ ...snapshot, positions: [noBasis, position] }, 'gain', 'en')).toEqual([
      position,
      noBasis,
    ]);
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
      const route = (accountId?: string) => (
        <QueryClientProvider client={client}>
          {withIntl(
            <InvestmentsScreen
              accountId={accountId}
              onScope={onScope}
              onAddDepot={jest.fn()}
              onImport={jest.fn()}
            />,
          )}
        </QueryClientProvider>
      );
      const tree = await render(route());
      expect(screen.queryByText("CHF 1'999.17")).toBeNull();
      await fireEvent.press(screen.getByRole('switch', { name: 'Hide amounts' }));
      expect(await screen.findByText("CHF 1'999.17")).toBeOnTheScreen();
      expect(await screen.findByText('Example Fund')).toBeOnTheScreen();
      expect(await screen.findByText('Swiss Fund')).toBeOnTheScreen();
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
