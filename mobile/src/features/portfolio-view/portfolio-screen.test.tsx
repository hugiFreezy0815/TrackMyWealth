import { onlineManager } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import { ApiError } from '@/api/client';
import { useAccountPositions, usePortfolios, usePortfolioView } from '@/api/queries/resources';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { renderWithIntl } from '@/i18n/test-utils';
import type {
  PortfolioAccount,
  PortfolioListItem,
  PortfolioSection,
  PortfolioView,
  Position,
  PositionsResponse,
} from './model';
import { PortfolioScreen } from './portfolio-screen';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('@/api/queries/resources', () => ({
  usePortfolios: jest.fn(),
  usePortfolioView: jest.fn(),
  useAccountPositions: jest.fn(),
}));
const mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textTertiary: '#53627A',
    bgSurface: '#FFFFFF',
    bgSubtle: '#ECF1F4',
    bgCanvas: '#F8FAFC',
    bgInverse: '#0B1C3F',
    bgOverlay: '#0B1C3F80',
    brandLink: '#006D64',
    brandPrimary: '#00857A',
    statusGain: '#007D55',
    statusLoss: '#C42F37',
    statusWarning: '#8F5200',
    statusInfo: '#006EA6',
    statusInfoSubtle: '#E7F4F9',
    statusWarningSubtle: '#FFF4E5',
    statusLossSubtle: '#FCE9E9',
    statusGainSubtle: '#E6F4EE',
    borderInteractive: '#64758A',
    borderDefault: '#E1E7EC',
    textOnBrand: '#FFFFFF',
  }),
}));

const LOSS = '#C42F37';

const makeAccount = (id: string, overrides: Partial<PortfolioAccount> = {}): PortfolioAccount => ({
  accountId: id,
  name: id,
  accountType: 'CASH',
  nature: 'ASSET',
  currency: 'EUR',
  balance: '100.00',
  valueInPortfolioCurrency: '100.00',
  valueKnown: true,
  valueStatus: 'CURRENT',
  valueSourceDate: '2026-09-14',
  warnings: [],
  positionsCount: null,
  positionsAsOf: null,
  totalReturn: null,
  dueDate: null,
  linkedPropertyAccountId: null,
  maskedIdentifier: null,
  reconciliationStatus: 'UNKNOWN',
  statementDate: null,
  ...overrides,
});
const makeSection = (
  name: PortfolioSection['section'],
  accounts: PortfolioAccount[],
  overrides: Partial<PortfolioSection> = {},
): PortfolioSection => ({
  section: name,
  subtotal: '100.00',
  subtotalInWorkspaceCurrency: '95.00',
  complete: true,
  valueStatus: 'CURRENT',
  accounts,
  ...overrides,
});
const view = (
  sections: PortfolioSection[],
  overrides: Partial<PortfolioView> = {},
): PortfolioView => ({
  portfolioId: 'p-1',
  name: 'Home',
  portfolioType: 'MIXED',
  portfolioTypeOverridden: false,
  personalAssetsDefault: false,
  portfolioCurrency: 'EUR',
  workspaceCurrency: 'CHF',
  asOf: '2026-11-09',
  total: '-200.00',
  totalInWorkspaceCurrency: '-190.00',
  complete: true,
  valueStatus: 'CURRENT',
  warnings: [],
  sections,
  institutionName: null,
  logoUrl: null,
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  lastImport: null,
  ...overrides,
});
const listItem: PortfolioListItem = {
  id: 'p-1',
  name: 'Home',
  total: '-200.00',
  portfolioCurrency: 'EUR',
  workspaceCurrency: 'CHF',
  totalInWorkspaceCurrency: '-190.00',
  complete: true,
  valueStatus: 'CURRENT',
  portfolioType: 'MIXED',
  personalAssetsDefault: false,
  asOf: '2026-11-09',
  institutionName: null,
  logoUrl: null,
} as unknown as PortfolioListItem;
const position = (id: string, overrides: Partial<Position> = {}): Position => ({
  securityId: id,
  displayName: `Fund ${id}`,
  isin: `IE00000000${id}`,
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
const depotSection = (overrides: Partial<PortfolioAccount> = {}) =>
  makeSection('DEPOT', [
    makeAccount('depot', {
      name: 'DKB Depot',
      accountType: 'SECURITIES',
      positionsCount: 3,
      positionsAsOf: '2026-09-30',
      ...overrides,
    }),
  ]);

type PositionsState = Partial<ReturnType<typeof useAccountPositions>>;
function setPositions(positions: Position[] | PositionsState) {
  const state: PositionsState = Array.isArray(positions)
    ? {
        data: {
          accountId: 'depot',
          currency: 'EUR',
          asOf: '2026-09-30',
          source: 'HOLDINGS_SNAPSHOT',
          complete: true,
          positions,
        } satisfies PositionsResponse,
        isPending: false,
        isError: false,
      }
    : positions;
  jest.mocked(useAccountPositions).mockReturnValue({
    refetch: jest.fn(),
    ...state,
  } as unknown as ReturnType<typeof useAccountPositions>);
}

function setData(
  data: PortfolioView | undefined,
  error?: Error,
  items: PortfolioListItem[] = [listItem],
) {
  jest.mocked(usePortfolios).mockReturnValue({
    data: items,
    isRefetching: false,
    refetch: jest.fn(),
  } as unknown as ReturnType<typeof usePortfolios>);
  jest.mocked(usePortfolioView).mockReturnValue({
    data,
    error: error ?? null,
    isPending: !data && !error,
    isError: !!error,
    isRefetching: false,
    refetch: jest.fn(),
    dataUpdatedAt: 1794200000000,
  } as unknown as ReturnType<typeof usePortfolioView>);
}

async function mount(locale: 'en' | 'de' = 'en') {
  const handlers = {
    onAccount: jest.fn(),
    onPortfolio: jest.fn(),
    onOverview: jest.fn(),
    onAddAccount: jest.fn(),
    onImport: jest.fn(),
    onMissing: jest.fn(),
  };
  const result = await renderWithIntl(
    <PortfolioScreen
      portfolioId="p-1"
      overviewName="All portfolios"
      onAddPortfolio={jest.fn()}
      {...handlers}
    />,
    locale,
  );
  return { ...result, ...handlers };
}

/** Every rendered text and accessibility label (the tree itself is circular around Modal). */
function renderedText(): string {
  const out: string[] = [];
  const walk = (node: unknown): void => {
    if (node == null) return;
    if (typeof node === 'string') return void out.push(node);
    if (Array.isArray(node)) return node.forEach(walk);
    const { props, children } = node as { props?: Record<string, unknown>; children?: unknown };
    for (const key of ['accessibilityLabel', 'aria-label']) {
      if (typeof props?.[key] === 'string') out.push(props[key] as string);
    }
    walk(children);
  };
  walk(screen.toJSON());
  return out.join('\n');
}

const color = (node: { props: { style?: unknown } }) =>
  (StyleSheet.flatten(node.props.style as never) as { color?: string }).color;

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(useAmountsHidden).mockReturnValue(false);
  mockWindow.width = 390;
  setPositions([]);
  // labelFor warns once per unknown enum value in development.
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});
afterEach(() => {
  jest.restoreAllMocks();
  onlineManager.setOnline(true);
});

describe('S2 Portfolio View acceptance (#493)', () => {
  it('shows exactly the sections the server sent, in its order', async () => {
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [makeAccount('Girokonto')]),
        makeSection('CARDS', [
          makeAccount('Visa', { accountType: 'CREDIT_CARD', balance: '-12.00' }),
        ]),
      ]),
    );
    await mount();
    const headings = screen.getAllByRole('header').map((node) => node.props.children);
    expect(headings).toEqual(['Home', 'Cash & savings', 'Credit card', 'Data source']);
    expect(screen.getByText('Girokonto')).toBeTruthy();
    expect(screen.getByText('Visa')).toBeTruthy();
  });

  it('shows a negative mortgage-heavy total with a minus sign that is read as "minus"', async () => {
    setData(
      view([
        makeSection(
          'PROPERTY_AND_MORTGAGE',
          [makeAccount('Mortgage', { accountType: 'MORTGAGE', balance: '-600.00' })],
          { subtotal: '-600.00' },
        ),
      ]),
    );
    await mount();
    const total = screen.getByTestId('portfolio-total');
    expect(total.props.children).toBe('−EUR 200.00');
    expect(total.props.accessibilityLabel).toMatch(/^minus/);
    expect(color(total)).toBe(LOSS);
    expect(screen.getByRole('button', { name: /Home total, minus/ })).toBeTruthy();
  });

  it('shows the stale source date, the due chip and no chip without a due date', async () => {
    const due = new Date();
    due.setDate(due.getDate() + 8);
    const iso = [
      due.getFullYear(),
      String(due.getMonth() + 1).padStart(2, '0'),
      String(due.getDate()).padStart(2, '0'),
    ].join('-');
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('Savings', { valueStatus: 'STALE', valueSourceDate: '2026-09-14' }),
        ]),
        makeSection('CARDS', [
          makeAccount('Visa', { accountType: 'CREDIT_CARD', dueDate: iso, balance: '-12.00' }),
          makeAccount('Amex', { accountType: 'CREDIT_CARD', dueDate: null, balance: '-1.00' }),
        ]),
      ]),
    );
    await mount();
    expect(screen.getAllByText(/Price from 14 Sep/).length).toBeGreaterThan(0);
    expect(screen.getByText('Due in 8 days')).toBeTruthy();
    const amex = screen.getByTestId('account-row-Amex');
    expect(within(amex).queryByText(/^Due/)).toBeNull();
    expect(within(amex).queryByText(/^Overdue/)).toBeNull();
  });

  it('marks an overdue card in the warning colour', async () => {
    setData(
      view([
        makeSection('CARDS', [
          makeAccount('Visa', { accountType: 'CREDIT_CARD', dueDate: '2020-01-31' }),
        ]),
      ]),
    );
    await mount();
    const chip = screen.getByText('Overdue since 31 Jan 2020');
    expect(color(chip)).toBe('#8F5200');
  });

  it('keeps unknown server sections and their rows visible under "Other"', async () => {
    setData(
      view([
        makeSection('FUTURE' as PortfolioSection['section'], [
          makeAccount('Unrecognised kind', { accountType: 'FUTURE_ACCOUNT' }),
        ]),
      ]),
    );
    const { onAccount } = await mount();
    expect(screen.getByText('Other')).toBeTruthy();
    expect(screen.getByText('Other account')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: /Unrecognised kind/ }));
    expect(onAccount).toHaveBeenCalledWith('Unrecognised kind');
  });
});

describe('S2 rows per section type', () => {
  it('shows identifier, reconciliation chip and the "≈" portfolio-currency value', async () => {
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('Girokonto', {
            maskedIdentifier: 'DE12 •••• 4471',
            reconciliationStatus: 'RECONCILED',
          }),
          makeAccount('USD cash', {
            currency: 'USD',
            balance: '50.00',
            valueInPortfolioCurrency: '46.00',
          }),
        ]),
      ]),
    );
    await mount();
    expect(screen.getByText('Current account · DE12 •••• 4471')).toBeTruthy();
    expect(screen.getByText('✓ Reconciled')).toBeTruthy();
    expect(screen.getByText('≈EUR 46.00')).toBeTruthy();
  });

  it('shows the depot row with position count and date, and its total return', async () => {
    setData(view([depotSection({ totalReturn: '7.84' })]));
    await mount();
    expect(screen.getByText('3 positions · As of 30 Sep 2026')).toBeTruthy();
    expect(screen.getByText('Total return')).toBeTruthy();
    expect(screen.getByText('+7.8%')).toBeTruthy();
  });

  it('indents a mortgage only under a property that is in the section', async () => {
    setData(
      view([
        makeSection('PROPERTY_AND_MORTGAGE', [
          makeAccount('house', { accountType: 'CUSTOM_ASSET' }),
          makeAccount('mortgage', { accountType: 'MORTGAGE', linkedPropertyAccountId: 'house' }),
          makeAccount('orphan', { accountType: 'MORTGAGE', linkedPropertyAccountId: 'sold' }),
        ]),
      ]),
    );
    await mount();
    const indent = (id: string) =>
      StyleSheet.flatten(screen.getByTestId(`account-row-${id}`).props.style).paddingStart;
    expect(indent('mortgage')).toBeGreaterThan(0);
    expect(indent('orphan')).toBe(0);
    expect(screen.getByText('Manual value · 14 Sep 2026')).toBeTruthy();
  });
});

describe('S2 depot positions (#478)', () => {
  it('shows the top five positions in server order with units, ISIN and signed gain', async () => {
    setData(view([depotSection()]));
    setPositions([
      position('1', { quantity: '1250.500000', unrealisedGainPercent: '11.24' }),
      position('2', { unrealisedGainPercent: '-3.75' }),
      position('3', { unrealisedGainPercent: null, unrealisedGain: null, priceCurrency: 'USD' }),
      position('4', { unrealisedGainPercent: null, unrealisedGain: null, costBasis: null }),
      position('5', { costBasisEstimated: true }),
      position('6'),
      position('7'),
    ]);
    const { onAccount } = await mount();
    const names = screen.getAllByText(/^Fund \d$/).map((node) => node.props.children);
    expect(names).toEqual(['Fund 1', 'Fund 2', 'Fund 3', 'Fund 4', 'Fund 5']);
    expect(screen.getByText("1'250.5 units · IE000000001")).toBeTruthy();
    expect(color(screen.getByText('+11.2%'))).toBe('#007D55');
    expect(color(screen.getByText('−3.8%'))).toBe(LOSS);
    expect(screen.getByText('Gain not available in USD')).toBeTruthy();
    expect(screen.getByText('No cost basis yet')).toBeTruthy();
    expect(screen.getByText('Estimated cost basis')).toBeTruthy();
    expect(
      screen.getByRole('button', {
        name: 'Fund 1, 100.00 euros, 1250.5 units, unrealised gain plus 11.2 percent',
      }),
    ).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Show all 7 positions' }));
    await fireEvent.press(screen.getByRole('button', { name: /^Fund 2,/ }));
    expect(onAccount.mock.calls).toEqual([['depot'], ['depot']]);
  });

  it('keeps the depot row visible when its positions fail and retries only them', async () => {
    setData(view([depotSection()]));
    const refetch = jest.fn();
    setPositions({
      data: undefined,
      isPending: false,
      isError: true,
      error: new ApiError('failed', 503, { code: 'INTERNAL' }),
      refetch,
    } as PositionsState);
    await mount();
    expect(screen.getByText('DKB Depot')).toBeTruthy();
    expect(screen.getByText('3 positions · As of 30 Sep 2026')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('does not request positions for an account without any', async () => {
    setData(view([depotSection({ positionsCount: 0 })]));
    await mount();
    expect(useAccountPositions).not.toHaveBeenCalled();
  });
});

describe('S2 header, explanation and data source', () => {
  it('shows the FX rate with its date, marked when carried forward or stale', async () => {
    setData(
      view([], {
        conversionRate: '0.9362000000',
        conversionRateDate: '2026-10-20',
        conversionRateCarriedForward: true,
      }),
    );
    await mount();
    expect(screen.queryByText('0.9362')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'FX details' }));
    const rate = screen.getByTestId('portfolio-fx-rate');
    expect(within(rate).getByText('1 EUR =')).toBeTruthy();
    expect(within(rate).getByText('0.9362')).toBeTruthy();
    expect(within(rate).getByText('CHF · 20 Oct 2026')).toBeTruthy();
    expect(within(rate).getByText('Carried-forward rate')).toBeTruthy();
  });

  it('marks a stale FX rate in the warning colour', async () => {
    setData(
      view([], {
        conversionRate: '0.9362',
        conversionRateDate: '2026-10-20',
        conversionRateCarriedForward: true,
        conversionRateStale: true,
      }),
    );
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'FX details' }));
    expect(color(screen.getByText('Outdated rate'))).toBe('#8F5200');
  });

  it('explains the total with warnings in plain words, unknown accounts and the ≈ value', async () => {
    setData(
      view(
        [
          makeSection('CASH_AND_SAVINGS', [
            makeAccount('Unpriced', { valueKnown: false, valueStatus: 'UNKNOWN', balance: null }),
          ]),
        ],
        {
          complete: false,
          warnings: ['OPEN_RECONCILIATION_DIFFERENCE', 'SOMETHING_NEW'],
        },
      ),
    );
    await mount();
    await fireEvent.press(screen.getByTestId('portfolio-total-action'));
    expect(screen.getByText('An account differs from its latest statement.')).toBeTruthy();
    expect(screen.getByText('Some account data needs a check.')).toBeTruthy();
    expect(screen.queryByText(/OPEN_RECONCILIATION_DIFFERENCE/)).toBeNull();
    expect(screen.getByText('Accounts with an unknown value')).toBeTruthy();
    expect(screen.getAllByText('Unpriced').length).toBe(2);
    expect(screen.getByTestId('portfolio-explanation-converted').props.children).toBe(
      '≈−CHF 190.00',
    );
  });

  it('explains a subtotal with its server-converted workspace value', async () => {
    setData(view([makeSection('CASH_AND_SAVINGS', [makeAccount('Girokonto')])]));
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: /^Cash & savings, 100.00 euros/ }));
    expect(screen.getByTestId('portfolio-explanation-converted').props.children).toBe('≈CHF 95.00');
    expect(screen.getByText('All available account values are included.')).toBeTruthy();
  });

  it('shows the last import and opens the import flow', async () => {
    setData(
      view([], {
        lastImport: {
          fileFormat: 'CSV',
          uploadedAt: '2026-10-18T08:00:00Z',
          committedAt: '2026-10-18T08:05:00Z',
          transactionCount: 41,
        } as PortfolioView['lastImport'],
      }),
    );
    const { onImport } = await mount();
    expect(screen.getByText(/^CSV: last import .* · 41 transactions$/)).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Import statement' }));
    expect(onImport).toHaveBeenCalledTimes(1);
  });

  it('disables Import while offline', async () => {
    onlineManager.setOnline(false);
    setData(view([]));
    const { onImport } = await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'Import statement' }));
    expect(onImport).not.toHaveBeenCalled();
  });

  it('shows no data source footer for the Personal assets default portfolio', async () => {
    setData(view([], { personalAssetsDefault: true, portfolioType: 'PERSONAL_ASSETS' }));
    await mount();
    expect(screen.getByText('Personal assets')).toBeTruthy();
    expect(screen.queryByText('Data source')).toBeNull();
  });

  it('puts details and data source in a 320 px right column from 1024 px', async () => {
    mockWindow.width = 1280;
    setData(view([], { institutionName: 'DKB' }));
    await mount();
    const side = screen.getByTestId('portfolio-side-column');
    expect(StyleSheet.flatten(side.props.style).width).toBe(320);
    expect(within(side).getByText('Mixed (suggested)')).toBeTruthy();
    expect(within(side).getByText('DKB')).toBeTruthy();
    expect(within(side).getByText('Data source')).toBeTruthy();
  });

  it('keeps the phone layout single-column below 1024 px', async () => {
    setData(view([]));
    await mount();
    expect(screen.queryByTestId('portfolio-side-column')).toBeNull();
    expect(screen.getByText('Data source')).toBeTruthy();
  });
});

describe('S2 privacy ("Hide amounts", FR-CLI-005)', () => {
  it('masks amounts, units, percentages and the FX rate, without a revealing loss colour', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    setData(
      view([depotSection({ totalReturn: '7.84', balance: '-5.00' })], {
        conversionRate: '0.9362',
        conversionRateDate: '2026-10-20',
      }),
    );
    setPositions([position('1', { quantity: '310', unrealisedGainPercent: '-3.75' })]);
    await mount();
    await fireEvent.press(screen.getByRole('button', { name: 'FX details' }));
    const total = screen.getByTestId('portfolio-total');
    expect(total.props.children).toBe('••••');
    expect(total.props.accessibilityLabel).toBe('hidden');
    expect(color(total)).not.toBe(LOSS);
    expect(screen.getByText('•••• units · IE000000001')).toBeTruthy();
    expect(renderedText()).not.toMatch(/200\.00|190\.00|−5|3[.,]8|7[.,]8|310|9362/);
    expect(screen.getByRole('button', { name: /^Fund 1, hidden, hidden units/ })).toBeTruthy();
  });
});

describe('S2 states and navigation', () => {
  it('shows an empty portfolio with Add account and no invented balance', async () => {
    setData(view([], { total: '0.00', totalInWorkspaceCurrency: '0.00' }));
    const { onAddAccount } = await mount('de');
    expect(screen.getByText('Noch keine Konten in Home')).toBeTruthy();
    expect(screen.getByTestId('portfolio-total').props.children).toBe('EUR 0.00');
    await fireEvent.press(screen.getByRole('button', { name: 'Konto hinzufügen' }));
    expect(onAddAccount).toHaveBeenCalledWith('p-1');
  });

  it('keeps the cached S1 header and offers Retry when the view fails', async () => {
    setData(undefined, new Error('network failed'));
    await mount();
    expect(screen.getAllByText('Home').length).toBeGreaterThan(0);
    expect(screen.getByTestId('portfolio-total').props.children).toBe('−EUR 200.00');
    const retry = screen.getByRole('button', { name: 'Try again' });
    await fireEvent.press(retry);
    expect(jest.mocked(usePortfolioView).mock.results[0].value.refetch).toHaveBeenCalled();
  });

  it('redirects a removed portfolio to the first tab once', async () => {
    setData(undefined, new ApiError('not found', 404, { code: 'NOT_FOUND' }));
    const { onMissing } = await mount();
    expect(screen.getByText('This portfolio no longer exists.')).toBeTruthy();
    await waitFor(() => expect(onMissing).toHaveBeenCalledTimes(1));
  });

  it('switches tabs: the first tab opens S1, another portfolio tab opens its S2', async () => {
    setData(view([]), undefined, [
      listItem,
      { ...listItem, id: 'p-2', name: 'Yuh' } as PortfolioListItem,
    ]);
    const { onOverview, onPortfolio } = await mount();
    expect(screen.getByRole('tab', { name: 'Home' })).toBeSelected();
    await fireEvent.press(screen.getByRole('tab', { name: 'All portfolios' }));
    await fireEvent.press(screen.getByRole('tab', { name: 'Yuh' }));
    expect(onOverview).toHaveBeenCalledTimes(1);
    expect(onPortfolio).toHaveBeenCalledWith('p-2');
  });
});
