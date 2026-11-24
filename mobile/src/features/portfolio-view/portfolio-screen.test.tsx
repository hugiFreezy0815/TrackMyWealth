import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { Platform, StyleSheet } from 'react-native';

import { ApiError } from '@/api/client';
import {
  useAccountPositions,
  usePortfolioHistory,
  usePortfolios,
  usePortfolioView,
} from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import type { components } from '@/api/schema';
import { selectHistoryPeriod } from '@/features/value-history/period';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import type { AppLocale } from '@/i18n';
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
  usePortfolioHistory: jest.fn(),
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
  totalReturnPercent: null,
  missingCostBasisCount: null,
  foreignCurrencyCostBasisCount: null,
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
// #527: the gain in the account currency mirrors the gain a test sets, unless it says other.
const position = (id: string, overrides: Partial<Position> = {}): Position => {
  const base = {
    securityId: id,
    displayName: `Fund ${id}`,
    isin: `IE00000000${id}`,
    quantity: '10.000000',
    price: '10.00',
    priceCurrency: 'EUR',
    value: '100.00',
    valueInAccountCurrency: '100.00',
    costBasis: '90.00',
    costBasisCurrency: 'EUR',
    costBasisInAccountCurrency: null,
    costBasisEstimated: false,
    costBasisSource: null,
    unrealisedGain: '10.00',
    unrealisedGainPercent: '11.11',
    valueStatus: 'CURRENT' as Position['valueStatus'],
    ...overrides,
  };
  return {
    unrealisedGainCurrency: base.unrealisedGain == null ? null : 'EUR',
    unrealisedGainInAccountCurrency: base.unrealisedGain,
    unrealisedGainInAccountCurrencyPercent: base.unrealisedGainPercent,
    ...base,
  };
};
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

async function mount(locale: AppLocale = 'en', client = new QueryClient()) {
  const handlers = {
    onAccount: jest.fn(),
    onPortfolio: jest.fn(),
    onOverview: jest.fn(),
    onAddAccount: jest.fn(),
    onImport: jest.fn(),
    onMissing: jest.fn(),
  };
  const result = await renderWithIntl(
    <QueryClientProvider client={client}>
      <PortfolioScreen
        portfolioId="p-1"
        overviewName="All portfolios"
        onAddPortfolio={jest.fn()}
        {...handlers}
      />
    </QueryClientProvider>,
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
  selectHistoryPeriod('1M');
  jest.mocked(useAmountsHidden).mockReturnValue(false);
  mockWindow.width = 390;
  setPositions([]);
  jest
    .mocked(usePortfolioHistory)
    .mockReturnValue({ isRefetching: false, refetch: jest.fn() } as unknown as ReturnType<
      typeof usePortfolioHistory
    >);
  // labelFor warns once per unknown enum value in development.
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});
afterEach(() => {
  jest.restoreAllMocks();
  onlineManager.setOnline(true);
});

describe('S2 Portfolio View acceptance (#493)', () => {
  it('loads the portfolio history and masks its chart labels and period changes', async () => {
    setData(view([]));
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    jest.mocked(usePortfolioHistory).mockReturnValue({
      data: {
        period: '1M',
        currency: 'EUR',
        from: '2026-10-08',
        to: '2026-11-08',
        points: [{ date: '2026-11-08', value: '998877.66', estimated: false }],
        change: {
          amount: '1122.33',
          percent: '9.24',
          contributions: null,
          capitalMovements: null,
          returnPercent: null,
          returnFrom: null,
          marketAndFx: null,
        },
        sections: [],
        complete: false,
        warnings: [
          'Currency conversion unavailable for portfolio capital flows.',
          'Private account 65432.10: value unknown',
        ],
        warningCodes: ['ATTRIBUTION_FX_UNAVAILABLE', 'VALUE_UNKNOWN'],
      },
      isPending: false,
      isError: false,
      isRefetching: false,
      refetch: jest.fn(),
    } as unknown as ReturnType<typeof usePortfolioHistory>);
    await mount();
    expect(usePortfolioHistory).toHaveBeenLastCalledWith(listItem.id, '1M', true);
    expect(
      screen.getByRole('image', { name: 'Value history. Amounts and dates are hidden.' }),
    ).toBeTruthy();
    expect(screen.queryByText(/9\.24%|1.?122\.33|998.?877\.66|8 Nov 2026/)).toBeNull();
    expect(screen.getByText('Some historical exchange rates are unavailable.')).toBeTruthy();
    expect(
      screen.getByText('A historical value is unavailable for at least one account.'),
    ).toBeTruthy();
    expect(screen.queryByText(/Private account|65432[.]10|Market & FX/)).toBeNull();
  });

  it('keeps the portfolio chart offline and updates it when the requested period loads', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
    });
    const actual =
      jest.requireActual<typeof import('@/api/queries/resources')>('@/api/queries/resources');
    jest.mocked(usePortfolioHistory).mockImplementation(actual.usePortfolioHistory);
    setData(view([]));
    const change = {
      amount: '11.25',
      percent: null,
      contributions: null,
      capitalMovements: null,
      returnPercent: null,
      returnFrom: null,
      marketAndFx: null,
    };
    const monthly: components['schemas']['ValueHistoryResponse'] = {
      period: '1M',
      currency: 'EUR',
      from: '2026-10-08',
      to: '2026-11-08',
      points: [{ date: '2026-11-08', value: '1100', estimated: false }],
      change,
      sections: [],
      complete: true,
      warnings: [],
      warningCodes: [],
    };
    const get = jest
      .spyOn(typedApi, 'get')
      .mockResolvedValueOnce(monthly)
      .mockResolvedValueOnce({ ...monthly, period: '1Y', change: { ...change, amount: '62.21' } });
    try {
      await mount('en', client);
      await screen.findByRole('image', { name: /plus 11[.]25/ });
      await act(() => onlineManager.setOnline(false));
      await fireEvent.press(screen.getByRole('radio', { name: '1Y, Past year' }));
      expect(screen.getByText('The 1Y period is unavailable offline. Showing 1M.')).toBeTruthy();
      expect(screen.getByRole('image', { name: /plus 11[.]25/ })).toBeTruthy();
      expect(get).toHaveBeenCalledTimes(1);

      await act(() => onlineManager.setOnline(true));
      await screen.findByRole('image', { name: /plus 62[.]21/ });
      expect(screen.queryByText(/period is unavailable offline/)).toBeNull();
      expect(get).toHaveBeenLastCalledWith(
        '/api/v1/portfolios/{portfolioId}/history',
        expect.objectContaining({ params: { portfolioId: 'p-1' }, query: { period: '1Y' } }),
      );
    } finally {
      await act(() => onlineManager.setOnline(true));
      get.mockRestore();
    }
  });

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

  it.each([
    ['en', /approximately minus 91\.25/],
    ['de', /ungefähr minus 91,25/],
  ] as const)('announces the server-converted row balance in %s', async (locale, label) => {
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('Foreign account', {
            currency: 'CHF',
            balance: '-100.00',
            valueInPortfolioCurrency: '-91.25',
          }),
          makeAccount('Same currency'),
          makeAccount('Missing conversion', { currency: 'CHF', valueInPortfolioCurrency: null }),
        ]),
      ]),
    );
    await mount(locale);
    const row = screen.getByRole('button', { name: /^Foreign account,/ });
    expect(row.props.accessibilityLabel).toMatch(label);
    for (const name of [/^Same currency,/, /^Missing conversion,/]) {
      expect(screen.getByRole('button', { name }).props.accessibilityLabel).not.toMatch(
        /approximately|ungefähr/,
      );
    }
  });

  it.each([
    ['the value is flagged unknown', { valueKnown: false }],
    ['the status is unrecognised', { valueStatus: 'FUTURE' as PortfolioAccount['valueStatus'] }],
  ] as const)('shows a single neutral placeholder when %s', async (_reason, overrides) => {
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('Foreign account', {
            currency: 'CHF',
            balance: '-100.00',
            valueInPortfolioCurrency: '-91.25',
            ...overrides,
          }),
        ]),
      ]),
    );
    await mount();
    const row = within(screen.getByTestId('account-row-Foreign account'));
    const button = row.getByRole('button', { name: /^Foreign account,/ });
    expect(button.props.accessibilityLabel).toMatch(/^Foreign account, Value unknown, [^0-9]+$/);
    const placeholders = row.getAllByText('–');
    expect(placeholders).toHaveLength(1);
    expect(color(placeholders[0])).not.toBe(LOSS);
    expect(row.queryByText(/100\.00|91\.25/)).toBeNull();
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
    expect(
      screen.getByRole('button', {
        name: 'USD cash, 50.00 US dollars, approximately 46.00 euros, Current account',
      }),
    ).toBeTruthy();
  });

  it.each([
    // Hidden: balance and converted value are both masked. Unknown: one placeholder (#606).
    { hidden: true, known: true, displayed: '••••', spoken: 'hidden', placeholders: 2 },
    { hidden: false, known: false, displayed: '–', spoken: 'Value unknown', placeholders: 1 },
  ])('keeps the spoken account balance private when the row shows $displayed', async (state) => {
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('USD cash', {
            currency: 'USD',
            balance: '50.00',
            valueInPortfolioCurrency: '46.00',
            valueKnown: state.known,
          }),
        ]),
      ]),
    );
    jest.mocked(useAmountsHidden).mockReturnValue(state.hidden);
    await mount();

    const button = screen.getByRole('button', {
      name: `USD cash, ${state.spoken}, Current account`,
    });
    expect(within(button).getAllByText(state.displayed)).toHaveLength(state.placeholders);
  });

  describe('on web (#617, WCAG 2.5.3 Label in Name)', () => {
    const originalOS = Platform.OS;
    beforeEach(() => {
      Platform.OS = 'web';
    });
    afterEach(() => {
      Platform.OS = originalOS;
    });

    const usdCash = () =>
      view([
        makeSection('CASH_AND_SAVINGS', [
          makeAccount('USD cash', {
            currency: 'USD',
            balance: '50.00',
            valueInPortfolioCurrency: '46.00',
          }),
        ]),
      ]);

    /** Text of the hidden node the row button references through aria-describedby. */
    function description(button: ReturnType<typeof screen.getByRole>): string {
      const id = button.props['aria-describedby'] as string;
      expect(id).toBeTruthy();
      const found: string[] = [];
      const walk = (node: unknown): void => {
        if (node == null || typeof node !== 'object') return;
        if (Array.isArray(node)) return node.forEach(walk);
        const { props, children } = node as {
          props?: Record<string, unknown>;
          children?: unknown[];
        };
        if (props?.nativeID === id) found.push((children ?? []).join(''));
        walk(children);
      };
      walk(screen.toJSON());
      expect(found).toHaveLength(1);
      return found[0];
    }

    /** Each amount in the row exposes exactly its visible text as its name. */
    function expectAmountsNamedByText(button: ReturnType<typeof screen.getByRole>, count: number) {
      const amounts = within(button)
        .getAllByText(/./)
        .filter((node) => 'aria-label' in node.props);
      expect(amounts).toHaveLength(count);
      for (const amount of amounts) {
        expect(amount.props['aria-label']).toBe(amount.props.children);
      }
      return amounts.map((amount) => amount.props.children as string);
    }

    it('names the row by its visible content and describes it with the spoken amounts', async () => {
      setData(usdCash());
      await mount();

      const button = screen.getByRole('button', {
        name: /^USD cash.*Current account.*USD 50\.00.*≈EUR 46\.00$/,
      });
      expect(button.props.accessibilityLabel).toBeUndefined();
      // The amounts expose their visible text, so the computed name matches what is shown.
      expect(expectAmountsNamedByText(button, 2)).toEqual([
        expect.stringMatching(/^USD\s50\.00$/),
        expect.stringMatching(/^≈EUR\s46\.00$/),
      ]);
      expect(description(button)).toBe('50.00 US dollars, approximately 46.00 euros');
    });

    it.each([
      { hidden: true, known: true, displayed: '••••', spoken: 'hidden' },
      { hidden: false, known: false, displayed: '–', spoken: 'Value unknown' },
    ])('exposes no private digits when the row shows $displayed', async (state) => {
      setData(
        view([
          makeSection('CASH_AND_SAVINGS', [
            makeAccount('USD cash', {
              currency: 'USD',
              balance: '50.00',
              valueInPortfolioCurrency: '46.00',
              valueKnown: state.known,
            }),
          ]),
        ]),
      );
      jest.mocked(useAmountsHidden).mockReturnValue(state.hidden);
      await mount();

      const button = screen.getByRole('button', { name: /^USD cash/ });
      expect(button.props.accessibilityLabel).toBeUndefined();
      const amounts = expectAmountsNamedByText(button, state.hidden ? 2 : 1);
      expect(amounts.every((text) => text.endsWith(state.displayed))).toBe(true);
      expect(description(button)).toBe(state.spoken);
      expect(renderedText()).not.toMatch(/50[.,]00|46[.,]00/);
    });
  });

  it('shows the depot gain amount and percentage from the server', async () => {
    setData(view([depotSection({ totalReturn: '3200', totalReturnPercent: '14.68' })]));
    await mount();
    expect(screen.getByText('3 positions · As of 30 Sep 2026')).toBeTruthy();
    expect(screen.getByText('Unrealised gain')).toBeTruthy();
    expect(screen.getByText(/3[,’']200[.]00/)).toBeTruthy();
    // One decimal, like the position rows below it.
    expect(screen.getByText('+14.7%')).toBeTruthy();
  });

  it('masks both unrealised gain figures when amounts are hidden', async () => {
    setData(view([depotSection({ totalReturn: '3200', totalReturnPercent: '14.68' })]));
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    await mount();

    const depot = within(screen.getByTestId('account-row-depot'));
    expect(depot.getByText('Unrealised gain')).toBeTruthy();
    expect(depot.getAllByLabelText('hidden').length).toBeGreaterThanOrEqual(3);
    expect(renderedText()).not.toMatch(/3[,’']200|14[.]68/);
  });

  it('shows the reason and affected position count when the gain is unavailable', async () => {
    setData(view([depotSection({ missingCostBasisCount: 1, foreignCurrencyCostBasisCount: 2 })]));
    await mount();

    expect(
      screen.getByText(
        /cost basis missing for 1 position · cost basis in EUR missing for 2 positions/,
      ),
    ).toBeTruthy();
    expect(screen.queryByText('Unrealised gain')).toBeNull();
  });

  it('explains why unknown holdings value prevents a gain', async () => {
    setData(view([depotSection({ balance: null, valueKnown: false, valueStatus: 'UNKNOWN' })]));
    await mount();
    expect(
      screen.getByText('Unrealised gain unavailable (incomplete holdings statement value)'),
    ).toBeTruthy();
  });

  it('explains a crypto account without any holdings statement the same way', async () => {
    setData(
      view([
        makeSection('CRYPTO', [
          makeAccount('crypto', {
            name: 'Wallet',
            accountType: 'CRYPTO',
            balance: null,
            valueKnown: false,
            valueStatus: 'UNKNOWN',
          }),
        ]),
      ]),
    );
    await mount();
    expect(screen.getByText('Unrealised gain unavailable (no holdings statement)')).toBeTruthy();
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
      position('2', { unrealisedGainPercent: '-3.75', costBasisSource: 'ORDERS' }),
      position('3', {
        unrealisedGainPercent: null,
        unrealisedGain: null,
        priceCurrency: 'USD',
        costBasisCurrency: null,
      }),
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
    expect(screen.getByText('Cost basis currency unknown')).toBeTruthy();
    expect(screen.getByText('No cost basis yet')).toBeTruthy();
    expect(screen.getByText('Estimated cost basis')).toBeTruthy();
    // US-15-15: Fund 2's cost basis is calculated from its orders.
    expect(screen.getAllByText('Cost basis from orders')).toHaveLength(1);
    expect(
      screen.getByRole('button', {
        name: /^Fund 1, 100.00 euros, 1250.5 units, Price: .*unrealised gain plus 11.2 percent$/,
      }),
    ).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Show all 7 positions' }));
    await fireEvent.press(screen.getByRole('button', { name: /^Fund 2,/ }));
    await fireEvent.press(screen.getByRole('button', { name: /^DKB Depot,/ }));
    expect(onAccount.mock.calls).toEqual([
      ['depot', 'positions'],
      ['depot', 'positions'],
      ['depot'],
    ]);
  });

  // #527: a gain is labelled with its own currency - a USD cost basis gives a USD gain in a EUR
  // depot - and the depot-currency gain wins when its cost there is known.
  it('labels each gain with its own currency and prefers the depot-currency gain', async () => {
    setData(view([depotSection()]));
    setPositions([
      position('1', {
        priceCurrency: 'USD',
        costBasisCurrency: 'USD',
        unrealisedGain: '25.00',
        unrealisedGainPercent: '33.33',
        unrealisedGainCurrency: 'USD',
        unrealisedGainInAccountCurrency: null,
        unrealisedGainInAccountCurrencyPercent: null,
      }),
      position('2', {
        priceCurrency: 'USD',
        costBasisCurrency: 'USD',
        costBasisInAccountCurrency: '72.00',
        unrealisedGain: '25.00',
        unrealisedGainPercent: '33.33',
        unrealisedGainCurrency: 'USD',
        unrealisedGainInAccountCurrency: '18.00',
        unrealisedGainInAccountCurrencyPercent: '25.00',
      }),
    ]);
    await mount();

    expect(screen.getByText('+USD 25.00')).toBeTruthy();
    expect(screen.getByText('+33.3%')).toBeTruthy();
    expect(screen.getByText('+EUR 18.00')).toBeTruthy();
    expect(screen.getByText('+25.0%')).toBeTruthy();
    expect(screen.queryByText('Cost basis currency unknown')).toBeNull();
  });

  // #527: a cost basis in a known currency that gives no gain names that currency rather than
  // calling it unknown.
  it('names a known cost basis currency that gives no gain', async () => {
    setData(view([depotSection()]));
    setPositions([
      position('1', {
        priceCurrency: 'USD',
        costBasisCurrency: 'GBP',
        costBasisInAccountCurrency: null,
        unrealisedGain: null,
        unrealisedGainPercent: null,
        unrealisedGainCurrency: null,
        unrealisedGainInAccountCurrency: null,
        unrealisedGainInAccountCurrencyPercent: null,
      }),
    ]);
    await mount();

    expect(screen.getByText('Gain not available for a cost basis in GBP')).toBeTruthy();
    expect(screen.queryByText('Cost basis currency unknown')).toBeNull();
  });

  it('gives no cost-basis reason for an unpriced position that has a cost basis', async () => {
    setData(view([depotSection()]));
    setPositions([
      position('1', {
        valueStatus: 'UNKNOWN',
        value: null,
        valueInAccountCurrency: null,
        unrealisedGain: null,
        unrealisedGainPercent: null,
      }),
    ]);
    await mount();
    expect(screen.queryByText('No cost basis yet')).toBeNull();
    expect(
      screen.getByRole('button', {
        name: /^Fund 1, Value unknown, 10 units, Price: .*unrealised gain Value unknown$/,
      }),
    ).toBeTruthy();
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

  it.each([
    ['en-GB', 'Home total, 1234.56 euros. Show details'],
    ['de-CH', 'Gesamtwert von Home, 1234.56 Euro. Details anzeigen'],
    ['de-DE', 'Gesamtwert von Home, 1234,56 Euro. Details anzeigen'],
  ])('starts the total button name with the visible amount in %s', async (locale, explanation) => {
    setData(view([], { total: '1234.56' }));
    await mount(locale);

    const visibleAmount = screen.getByTestId('portfolio-total').props.children;
    const button = screen.getByRole('button', { name: `${visibleAmount}. ${explanation}` });
    expect(button).toHaveProp('testID', 'portfolio-total-action');
  });

  it.each([
    { hidden: true, display: '••••', spoken: 'hidden', valueStatus: 'CURRENT' },
    { hidden: false, display: '–', spoken: 'Value unknown', valueStatus: 'UNKNOWN' },
  ] as const)('names the $display total and opens its explanation', async (state) => {
    // Keep a numeric payload in both cases to check that the status and privacy setting win.
    setData(view([], { total: '1234.56', valueStatus: state.valueStatus }));
    jest.mocked(useAmountsHidden).mockReturnValue(state.hidden);
    await mount();

    expect(screen.getByTestId('portfolio-total')).toHaveTextContent(state.display);
    const button = screen.getByRole('button', {
      name: `${state.display}. Home total, ${state.spoken}. Show details`,
    });
    expect(button).toHaveProp('testID', 'portfolio-total-action');
    await fireEvent.press(button);
    expect(screen.getByText('Portfolio total')).toBeTruthy();
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

  it.each([
    ['en-GB', 'Cash & savings, 9876.54 euros. Show details'],
    ['de-CH', 'Bargeld & Sparen, 9876.54 Euro. Details anzeigen'],
    ['de-DE', 'Bargeld & Sparen, 9876,54 Euro. Details anzeigen'],
  ])('names the visible subtotal and opens its explanation in %s', async (locale, explanation) => {
    setData(
      view([makeSection('CASH_AND_SAVINGS', [makeAccount('Girokonto')], { subtotal: '9876.54' })]),
    );
    await mount(locale);

    const subtotal = screen.getByTestId('portfolio-section-subtotal');
    const button = screen.getByRole('button', {
      name: `${subtotal.props.children}. ${explanation}`,
    });
    expect(within(button).getByTestId('portfolio-section-subtotal')).toBe(subtotal);
    await fireEvent.press(button);
    expect(screen.getByTestId('portfolio-explanation-converted')).toHaveTextContent('≈CHF 95.00');
  });

  it.each([
    { hidden: true, display: '••••', spoken: 'hidden', valueStatus: 'CURRENT' },
    { hidden: false, display: '–', spoken: 'Value unknown', valueStatus: 'UNKNOWN' },
  ] as const)('names the $display subtotal and opens its explanation', async (state) => {
    // A numeric server payload must stay hidden when privacy mode or UNKNOWN applies.
    setData(
      view([
        makeSection('CASH_AND_SAVINGS', [makeAccount('Girokonto')], {
          subtotal: '9876.54',
          valueStatus: state.valueStatus,
        }),
      ]),
    );
    jest.mocked(useAmountsHidden).mockReturnValue(state.hidden);
    await mount();

    const button = screen.getByRole('button', {
      name: `${state.display}. Cash & savings, ${state.spoken}. Show details`,
    });
    expect(within(button).getByTestId('portfolio-section-subtotal')).toHaveTextContent(
      state.display,
    );
    await fireEvent.press(button);
    expect(screen.getByTestId('portfolio-explanation-converted')).toHaveTextContent(state.display);
  });

  it.each([
    [
      'en',
      'Institution DKB · Germany · portfolio currency EUR',
      'CSV: last import 17 Oct 2026 · 41 transactions',
    ],
    [
      'de',
      'Institut DKB · Deutschland · Portfoliowährung EUR',
      'CSV: letzter Import 17.10.2026 · 41 Buchungen',
    ],
  ] as const)(
    'localizes the country and uses the local commit day across UTC midnight in %s',
    async (locale, subtitle, lastImport) => {
      setData(
        view([], {
          institutionName: 'DKB',
          lastImport: {
            fileFormat: 'CSV',
            uploadedAt: '2026-10-16T12:00:00Z',
            committedAt: '2026-10-18T00:15:00Z',
            transactionCount: 41,
          },
        }),
        undefined,
        [{ ...listItem, country: 'DE' }],
      );
      const DateTimeFormat = Intl.DateTimeFormat;
      const formatInDeviceZone = (
        locales?: Intl.LocalesArgument,
        options?: Intl.DateTimeFormatOptions,
      ) =>
        new DateTimeFormat(locales, {
          ...options,
          timeZone: options?.timeZone ?? 'America/Los_Angeles',
        });
      Intl.DateTimeFormat = new Proxy(DateTimeFormat, {
        apply: (_target, _thisArg, [locales, options]) => formatInDeviceZone(locales, options),
        construct: (_target, [locales, options]) => formatInDeviceZone(locales, options),
      });
      try {
        await mount(locale);
        expect(screen.getByText(subtitle)).toBeTruthy();
        expect(screen.getByText(lastImport)).toBeTruthy();
      } finally {
        Intl.DateTimeFormat = DateTimeFormat;
      }
    },
  );

  it('shows the empty import state and opens the import flow', async () => {
    setData(view([]));
    const { onImport } = await mount();
    expect(screen.getByText('No import yet')).toBeTruthy();
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
      view(
        [
          depotSection({
            totalReturn: '7.84',
            balance: '-5.00',
            currency: 'CHF',
            valueInPortfolioCurrency: '-4.68',
          }),
        ],
        {
          conversionRate: '0.9362',
          conversionRateDate: '2026-10-20',
        },
      ),
    );
    setPositions([position('1', { quantity: '310', unrealisedGainPercent: '-3.75' })]);
    await mount();
    const accountButton = screen.getByRole('button', { name: /^DKB Depot,/ });
    expect(accountButton.props.accessibilityLabel).toBe(
      'DKB Depot, hidden, 3 positions · As of 30 Sep 2026',
    );
    await fireEvent.press(screen.getByRole('button', { name: 'FX details' }));
    const total = screen.getByTestId('portfolio-total');
    expect(total.props.children).toBe('••••');
    expect(total.props.accessibilityLabel).toBe('hidden');
    expect(color(total)).not.toBe(LOSS);
    expect(screen.getByText('•••• units · IE000000001')).toBeTruthy();
    expect(renderedText()).not.toMatch(/200\.00|190\.00|−5|4[.,]68|3[.,]8|7[.,]8|310|9362/);
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
