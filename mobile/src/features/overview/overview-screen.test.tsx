import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';
import { StyleSheet } from 'react-native';

import { ApiError } from '@/api/client';
import {
  useAccounts,
  useCashFlow,
  useNetWorth,
  useNetWorthHistory,
  usePortfolios,
  useWorkspace,
} from '@/api/queries/resources';
import { typedApi } from '@/api/typed-api';
import type { components } from '@/api/schema';
import { Colors } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import en from '@/i18n/en.json';
import { selectHistoryPeriod } from '@/features/value-history/period';
import de from '@/i18n/de.json';
import { OverviewScreen, type OverviewScreenProps } from './overview-screen';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/privacy/amount-visibility', () => ({
  useAmountsHidden: jest.fn(() => false),
}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
const mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));
jest.mock('@/api/queries/resources', () => ({
  useWorkspace: jest.fn(),
  usePortfolios: jest.fn(),
  useNetWorth: jest.fn(),
  useNetWorthHistory: jest.fn(),
  useAccounts: jest.fn(),
  useCashFlow: jest.fn(),
}));

type Workspace = components['schemas']['WorkspaceResponse'];
type Portfolio = components['schemas']['PortfolioListItemResponse'];
type NetWorth = components['schemas']['NetWorthResponse'];
type CashFlow = components['schemas']['CashFlowResponse'];
type Account = components['schemas']['AccountListItemResponse'];

const workspace: Workspace = { id: 'workspace-1', name: 'Family Hug', currency: 'CHF', version: 3 };
const portfolio: Portfolio = {
  id: 'portfolio-1',
  version: 4,
  name: 'Euro Bank',
  catalogueInstitutionId: null,
  institutionType: 'BANK',
  country: 'DE',
  logoUrl: null,
  portfolioType: 'BANK',
  portfolioTypeOverridden: false,
  personalAssetsDefault: false,
  activeAccountCount: 2,
  portfolioCurrency: 'EUR',
  total: '2350.40',
  workspaceCurrency: 'CHF',
  totalInWorkspaceCurrency: '2244.33',
  complete: true,
  valueStatus: 'CURRENT',
  asOf: '2026-11-08',
  institutionName: null,
  change: { period: '1M', amount: '12.00', percent: '0.51' },
};
const accountValuation: NetWorth['accounts'][number] = {
  accountId: 'account-1',
  name: 'Mortgage',
  nature: 'LIABILITY',
  nativeCurrency: 'CHF',
  currency: 'CHF',
  // Liabilities are sent as positive magnitudes (AccountValuation, DM-12).
  value: '400000.00',
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  valueKnown: true,
  valueBasis: null,
  valueSourceDate: null,
  valueSourceStale: false,
  warnings: [],
};
const netWorth: NetWorth = {
  currency: 'CHF',
  asOf: '2026-11-08',
  totalAssets: '1079362.92',
  totalLiabilities: '601202.36',
  netWorth: '478160.56',
  complete: true,
  approximate: true,
  warnings: [],
  accounts: [accountValuation],
  sections: [
    {
      section: 'CASH_AND_SAVINGS',
      subtotal: '45210.50',
      complete: true,
      valueStatus: 'CURRENT',
      accountCount: 2,
    },
  ],
};
const cashFlow: CashFlow = {
  month: '2026-11',
  complete: true,
  income: [
    {
      currency: 'CHF',
      amount: '5000.00',
      valueKnown: true,
      conversionRateStale: false,
      conversionRateCarriedForward: false,
    },
  ],
  spending: [
    {
      currency: 'CHF',
      amount: '3000.00',
      valueKnown: true,
      conversionRateStale: false,
      conversionRateCarriedForward: false,
    },
  ],
  saving: [
    {
      currency: 'CHF',
      amount: '2000.00',
      valueKnown: true,
      conversionRateStale: false,
      conversionRateCarriedForward: false,
    },
  ],
  pendingReview: [],
};

function success<T>(data: T) {
  return {
    data,
    dataUpdatedAt: 1794150000000,
    isPending: false,
    isSuccess: true,
    isError: false,
    isRefetching: false,
    error: null,
    refetch: jest.fn(async () => ({ data })),
  };
}
function failure(error: Error) {
  return {
    data: undefined,
    dataUpdatedAt: 0,
    isPending: false,
    isSuccess: false,
    isError: true,
    isRefetching: false,
    error,
    refetch: jest.fn(async () => ({ error })),
  };
}
function setFixtures() {
  jest
    .mocked(useNetWorthHistory)
    .mockReturnValue(success(undefined) as unknown as ReturnType<typeof useNetWorthHistory>);
  jest
    .mocked(useWorkspace)
    .mockReturnValue(success(workspace) as unknown as ReturnType<typeof useWorkspace>);
  jest
    .mocked(usePortfolios)
    .mockReturnValue(success([portfolio]) as unknown as ReturnType<typeof usePortfolios>);
  jest
    .mocked(useNetWorth)
    .mockReturnValue(success(netWorth) as unknown as ReturnType<typeof useNetWorth>);
  jest
    .mocked(useAccounts)
    .mockReturnValue(success([]) as unknown as ReturnType<typeof useAccounts>);
  jest
    .mocked(useCashFlow)
    .mockReturnValue(success(cashFlow) as unknown as ReturnType<typeof useCashFlow>);
}
async function renderOverview(
  locale: 'en' | 'de' = 'en',
  props: Partial<OverviewScreenProps> = {},
  queryClient = new QueryClient(),
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <IntlProvider locale={locale} messages={locale === 'de' ? de : en}>
        <OverviewScreen onAddPortfolio={jest.fn()} onReviewAccount={jest.fn()} {...props} />
      </IntlProvider>
    </QueryClientProvider>,
  );
}

const unreconciledAccount = (id: string, name: string): Account => ({
  id,
  version: 1,
  name,
  accountType: 'CURRENT',
  nature: 'ASSET',
  portfolioId: 'portfolio-1',
  portfolioName: 'Euro Bank',
  currency: 'CHF',
  archived: false,
  hasTransactions: true,
  hasStatementCycle: false,
  hasAmortisation: false,
  savingsPocketAccountId: null,
  balance: '10.00',
  workspaceCurrency: 'CHF',
  balanceInWorkspaceCurrency: '10.00',
  valueKnown: true,
  valueStatus: 'UNRECONCILED',
  valueBasis: 'LEDGER',
  asOf: '2026-11-08',
  valueSourceDate: null,
  warnings: [],
});
function mockNetWorth(overrides: Partial<NetWorth>) {
  jest
    .mocked(useNetWorth)
    .mockReturnValue(
      success({ ...netWorth, ...overrides }) as unknown as ReturnType<typeof useNetWorth>,
    );
}
function liability(accountId: string, name: string, value: string | null) {
  return { ...accountValuation, accountId, name, value };
}

describe('Overview S1 (#489)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockWindow.width = 390;
    setFixtures();
    selectHistoryPeriod('1M');
    jest.mocked(useAmountsHidden).mockReturnValue(false);
  });

  it('shows the exact overview name, approximate net worth, section and converted portfolio amount', async () => {
    await renderOverview();
    expect(screen.getByRole('tab', { name: 'Family Hug' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Net worth.*478160.56.*estimated/i })).toBeTruthy();
    expect(screen.getByText('Estimated')).toBeTruthy();
    expect(screen.getByText('Cash & savings')).toBeTruthy();
    expect(screen.getByText(/≈CHF/)).toBeTruthy();
    expect(screen.getAllByText('Euro Bank')[0]).toBeTruthy();
  });

  it('renders server changes for the active period and drops them while switching', async () => {
    const change = {
      amount: '42.75',
      percent: '3.2',
      contributions: '1.50',
      capitalMovements: null,
      marketAndFx: null,
    };
    const history: components['schemas']['ValueHistoryResponse'] = {
      period: '1M',
      currency: 'CHF',
      from: '2026-10-08',
      to: '2026-11-08',
      points: [
        { date: '2026-10-08', value: '1000', estimated: true },
        { date: '2026-11-08', value: '1200', estimated: false },
      ],
      change,
      sections: [{ section: 'CASH_AND_SAVINGS', change: { ...change, amount: '17.12' } }],
      complete: false,
      warnings: [
        'Currency conversion unavailable for contributions.',
        'Private account 65432.10: value unknown',
        'Transactions are pending review; contribution attribution is incomplete.',
      ],
    };
    jest
      .mocked(useNetWorthHistory)
      .mockImplementation(
        (_currency, period) =>
          success(period === '1M' ? history : undefined) as unknown as ReturnType<
            typeof useNetWorthHistory
          >,
      );
    await renderOverview();
    expect(useNetWorthHistory).toHaveBeenLastCalledWith('CHF', '1M', true);
    expect(screen.getByRole('image', { name: /8 Oct 2026.*plus 42.75.*estimated/i })).toBeTruthy();
    expect(screen.getByText('+3.2%')).toBeTruthy();
    expect(screen.getByText('+0.5%')).toBeTruthy();
    expect(screen.getByLabelText('plus 17.12 Swiss francs')).toBeTruthy();
    expect(screen.getByText('Net saving')).toBeTruthy();
    expect(screen.queryByText('Market & FX')).toBeNull();
    expect(screen.getByText(/parts of the change are incomplete/)).toBeTruthy();
    expect(screen.getByText('Some historical exchange rates are unavailable.')).toBeTruthy();
    expect(
      screen.getByText('A historical value is unavailable for at least one account.'),
    ).toBeTruthy();
    expect(screen.getByText(/Some transactions still need review/)).toBeTruthy();
    expect(screen.queryByText(/Private account|65432[.]10/)).toBeNull();

    await fireEvent.press(screen.getByRole('radio', { name: '1W, Past week' }));
    expect(useNetWorthHistory).toHaveBeenLastCalledWith('CHF', '1W', true);
    expect(screen.queryByText('+0.5%')).toBeNull();
    expect(screen.queryByLabelText('plus 17.12 Swiss francs')).toBeNull();
  });

  it('keeps the monthly chart and section changes offline until the yearly history loads', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
    });
    const actual =
      jest.requireActual<typeof import('@/api/queries/resources')>('@/api/queries/resources');
    jest.mocked(useNetWorthHistory).mockImplementation(actual.useNetWorthHistory);
    const change = {
      amount: '10.25',
      percent: null,
      contributions: null,
      capitalMovements: null,
      marketAndFx: null,
    };
    const monthly: components['schemas']['ValueHistoryResponse'] = {
      period: '1M',
      currency: 'CHF',
      from: '2026-10-08',
      to: '2026-11-08',
      points: [{ date: '2026-11-08', value: '1200', estimated: false }],
      change,
      sections: [{ section: 'CASH_AND_SAVINGS', change: { ...change, amount: '13.40' } }],
      complete: true,
      warnings: [],
    };
    const get = jest
      .spyOn(typedApi, 'get')
      .mockResolvedValueOnce(monthly)
      .mockResolvedValueOnce({
        ...monthly,
        period: '1Y',
        change: { ...change, amount: '52.21' },
        sections: [],
      });
    try {
      await renderOverview('en', {}, client);
      await screen.findByRole('image', { name: /plus 10[.]25/ });
      await act(() => onlineManager.setOnline(false));
      await fireEvent.press(screen.getByRole('radio', { name: '1Y, Past year' }));
      expect(screen.getByText('The 1Y period is unavailable offline. Showing 1M.')).toBeTruthy();
      expect(screen.getByRole('image', { name: /plus 10[.]25/ })).toBeTruthy();
      expect(screen.getByLabelText('plus 13.40 Swiss francs')).toBeTruthy();
      expect(get).toHaveBeenCalledTimes(1);

      await act(() => onlineManager.setOnline(true));
      await screen.findByRole('image', { name: /plus 52[.]21/ });
      expect(screen.queryByText(/period is unavailable offline/)).toBeNull();
      expect(screen.queryByLabelText('plus 13.40 Swiss francs')).toBeNull();
      expect(get).toHaveBeenLastCalledWith(
        '/api/v1/net-worth/history',
        expect.objectContaining({ query: { currency: 'CHF', period: '1Y' } }),
      );
    } finally {
      await act(() => onlineManager.setOnline(true));
      get.mockRestore();
    }
  });

  it('falls back to the German tab label without exposing technical terminology', async () => {
    jest
      .mocked(useWorkspace)
      .mockReturnValue(
        success({ ...workspace, name: null }) as unknown as ReturnType<typeof useWorkspace>,
      );
    await renderOverview('de');
    expect(screen.getByRole('tab', { name: 'Alle Portfolios' })).toBeTruthy();
  });

  it('keeps net worth and portfolios when cash flow fails, with its own retry action', async () => {
    jest
      .mocked(useCashFlow)
      .mockReturnValue(
        failure(new ApiError('server error', 503)) as unknown as ReturnType<typeof useCashFlow>,
      );
    await renderOverview();
    expect(screen.getByRole('button', { name: /Net worth.*478160.56/i })).toBeTruthy();
    expect(screen.getAllByText('Euro Bank')[0]).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(jest.mocked(useCashFlow).mock.results[0].value.refetch).toHaveBeenCalledTimes(1);
  });

  it('renders the first-run steps and starts the portfolio flow instead of guessing a zero value', async () => {
    jest
      .mocked(usePortfolios)
      .mockReturnValue(success([]) as unknown as ReturnType<typeof usePortfolios>);
    jest
      .mocked(useNetWorth)
      .mockReturnValue(
        success({ ...netWorth, accounts: [], sections: [] }) as unknown as ReturnType<
          typeof useNetWorth
        >,
      );
    const add = jest.fn();
    await renderOverview('en', { onAddPortfolio: add });
    expect(screen.getByText('No figures yet')).toBeTruthy();
    expect(screen.getByText(/about 10 minutes/)).toBeTruthy();
    expect(screen.queryByText("CHF\u00a0478'160.56")).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Add your first portfolio' }));
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('shows unknown-account names on the incomplete explanation sheet', async () => {
    jest.mocked(useNetWorth).mockReturnValue(
      success({
        ...netWorth,
        complete: false,
        accounts: [
          ...netWorth.accounts,
          {
            ...accountValuation,
            accountId: 'unknown',
            name: 'Missing valuation',
            value: null,
            valueKnown: false,
          },
        ],
      }) as unknown as ReturnType<typeof useNetWorth>,
    );
    await renderOverview();
    await fireEvent.press(screen.getByText('Not all values are included'));
    expect(screen.getByText('Missing valuation')).toBeTruthy();
  });

  it('masks figures in the rendered and accessible output when privacy is enabled', async () => {
    jest.mocked(useAmountsHidden).mockReturnValue(true);
    const { toJSON } = await renderOverview('en');
    expect(screen.getAllByText('••••').length).toBeGreaterThan(2);
    // Inspect only text and accessibility metadata exposed to users. React component types
    // contain circular references and are not part of the rendered or spoken output.
    const exposed: string[] = [];
    const inspect = (node: unknown): void => {
      if (typeof node === 'string') {
        exposed.push(node);
        return;
      }
      if (Array.isArray(node)) {
        node.forEach(inspect);
        return;
      }
      if (!node || typeof node !== 'object') return;
      const element = node as { props?: Record<string, unknown>; children?: unknown };
      for (const key of ['accessibilityLabel', 'accessibilityHint']) {
        const value = element.props?.[key];
        if (typeof value === 'string') exposed.push(value);
      }
      inspect(element.children);
    };
    inspect(toJSON());
    const tree = exposed.join(' ');
    // Display text groups digits; spoken labels (#486 a11yAmount) use ungrouped digits.
    for (const digits of ["478'160.56", '478160.56', "1'079'362.92", '1079362.92']) {
      expect(tree).not.toContain(digits);
    }
    for (const digits of [
      "2'350.40",
      '2350.40',
      "400'000.00",
      '400000.00',
      "5'000.00",
      '5000.00',
    ]) {
      expect(tree).not.toContain(digits);
    }
    expect(screen.getByTestId('overview-liabilities-includes').props.children).toBe(
      'Includes Mortgage (••••)',
    );
  });

  it('changes only the selected currency for the two affected resource hooks', async () => {
    await renderOverview();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose display currency' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'EUR' }));
    expect(useNetWorth).toHaveBeenLastCalledWith('EUR', { enabled: true });
    expect(useCashFlow).toHaveBeenLastCalledWith(expect.any(String), 'EUR', { enabled: true });
  });

  it('navigates from a portfolio tab and the portfolio row to the new S2 screen', async () => {
    const navigate = jest.fn();
    await renderOverview('en', { onPortfolio: navigate });
    await fireEvent.press(screen.getByRole('tab', { name: 'Euro Bank' }));
    expect(navigate).toHaveBeenCalledWith('portfolio-1');
    await fireEvent.press(screen.getByRole('button', { name: 'Euro Bank' }));
    expect(navigate).toHaveBeenCalledTimes(2);
  });

  it('waits for the workspace currency instead of firing currency-less figure requests', async () => {
    jest.mocked(useWorkspace).mockReturnValue({
      ...success(undefined),
      isPending: true,
      isSuccess: false,
    } as unknown as ReturnType<typeof useWorkspace>);
    await renderOverview();
    expect(useNetWorth).toHaveBeenLastCalledWith(undefined, { enabled: false });
    expect(useCashFlow).toHaveBeenLastCalledWith(expect.any(String), undefined, {
      enabled: false,
    });
  });

  it('asks for the current local month', async () => {
    jest.useFakeTimers({ now: new Date(2026, 11, 31, 23, 30) });
    try {
      await renderOverview();
      expect(useCashFlow).toHaveBeenLastCalledWith('2026-12', 'CHF', { enabled: true });
    } finally {
      jest.useRealTimers();
    }
  });

  it('does not show the converted line when the portfolio and workspace currency match', async () => {
    jest
      .mocked(usePortfolios)
      .mockReturnValue(
        success([
          { ...portfolio, portfolioCurrency: 'CHF', total: '2244.33' },
        ]) as unknown as ReturnType<typeof usePortfolios>,
      );
    await renderOverview();
    expect(screen.getAllByText('Euro Bank')[0]).toBeTruthy();
    expect(screen.queryByText(/≈/)).toBeNull();
  });

  it('names the two largest liabilities and counts the rest in the "Includes" line', async () => {
    mockNetWorth({
      accounts: [
        liability('visa', 'DKB Visa', '1202.36'),
        liability('mortgage', 'Mortgage', '600000.00'),
        liability('loan', 'Car loan', '5000.00'),
        { ...liability('unknown', 'Unvalued', null), valueKnown: false },
      ],
    });
    await renderOverview();
    const line = screen.getByTestId('overview-liabilities-includes');
    expect(line.props.children).toMatch(
      /^Includes Mortgage \(CHF\s600'000\.00\), Car loan \(CHF\s5'000\.00\) and 1 more$/,
    );
    expect(line.props.accessibilityLabel).toMatch(
      /^Includes Mortgage \(600000\.00 Swiss francs\), Car loan/,
    );
  });

  it('uses the two-item and German forms of the "Includes" line', async () => {
    mockNetWorth({
      accounts: [
        liability('visa', 'DKB Visa', '1202.36'),
        liability('mortgage', 'Hypothek', '600000.00'),
      ],
    });
    await renderOverview('de');
    expect(screen.getByTestId('overview-liabilities-includes').props.children).toMatch(
      /^Enthält Hypothek \(CHF\s600'000\.00\) und DKB Visa \(CHF\s1'202\.36\)$/,
    );
    expect(screen.getByTestId('overview-liabilities-includes').props.accessibilityLabel).toMatch(
      /^Enthält Hypothek \(600000,00 Schweizer Franken\)/,
    );
  });

  it('hides the "Includes" line when there are no liabilities and keeps the bar decorative', async () => {
    mockNetWorth({ accounts: [], totalLiabilities: '0.00', netWorth: '1079362.92' });
    await renderOverview();
    expect(screen.queryByTestId('overview-liabilities-includes')).toBeNull();
    const bar = screen.getByTestId('overview-assets-liabilities-bar', {
      includeHiddenElements: true,
    });
    expect(bar.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('shows a negative net worth with its sign in the loss colour, never as zero', async () => {
    mockNetWorth({ netWorth: '-12500.40', approximate: false });
    await renderOverview();
    const amount = screen.getByTestId('overview-net-worth');
    expect(amount.props.children).toMatch(/^\u2212CHF\s12'500\.40$/);
    expect(StyleSheet.flatten(amount.props.style).color).toBe(Colors.light.statusLoss);
    expect(screen.getByRole('button', { name: /^Net worth, minus 12500\.40/ })).toBeTruthy();
  });

  it('keeps a positive net worth in the primary text colour', async () => {
    await renderOverview();
    const amount = screen.getByTestId('overview-net-worth');
    expect(StyleSheet.flatten(amount.props.style).color).toBe(Colors.light.textPrimary);
  });

  it('opens the one unreconciled account directly from "Review"', async () => {
    jest
      .mocked(useAccounts)
      .mockReturnValue(
        success([unreconciledAccount('acc-1', 'PostFinance Privat')]) as unknown as ReturnType<
          typeof useAccounts
        >,
      );
    const review = jest.fn();
    await renderOverview('en', { onReviewAccount: review });
    expect(screen.getByText('1 account differs from its statement')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Review' }));
    expect(review).toHaveBeenCalledWith('acc-1');
  });

  it('lists several unreconciled accounts in a sheet, each opening its account', async () => {
    jest
      .mocked(useAccounts)
      .mockReturnValue(
        success([
          unreconciledAccount('acc-1', 'PostFinance Privat'),
          unreconciledAccount('acc-2', 'Yuh'),
          { ...unreconciledAccount('acc-3', 'Archived'), archived: true },
        ]) as unknown as ReturnType<typeof useAccounts>,
      );
    const review = jest.fn();
    await renderOverview('en', { onReviewAccount: review });
    expect(screen.getByText('2 accounts differ from their statement')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Review' }));
    expect(review).not.toHaveBeenCalled();
    expect(screen.queryByText(/Archived/)).toBeNull();
    await fireEvent.press(screen.getByText('Yuh · Euro Bank'));
    expect(review).toHaveBeenCalledWith('acc-2');
  });

  it('explains data-quality warnings in plain words and never shows the server codes', async () => {
    mockNetWorth({ warnings: ['OPEN_RECONCILIATION_DIFFERENCE', 'SOME_FUTURE_CODE'] });
    // labelFor warns once per unknown value in development builds (#452): expected here.
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    await renderOverview();
    await fireEvent.press(screen.getByRole('button', { name: /^Net worth,/ }));
    expect(screen.getByText('An account differs from its latest statement.')).toBeTruthy();
    expect(screen.getByText('Some account data needs a check.')).toBeTruthy();
    expect(screen.queryByText(/OPEN_RECONCILIATION_DIFFERENCE|SOME_FUTURE_CODE/)).toBeNull();
    expect(warn).toHaveBeenCalledWith('Unknown API enum value:', 'SOME_FUTURE_CODE');
    warn.mockRestore();
  });

  it('shows the server zero and the "add an account" hint for portfolios without accounts', async () => {
    jest
      .mocked(usePortfolios)
      .mockReturnValue(
        success([{ ...portfolio, activeAccountCount: 0, total: '0.00' }]) as unknown as ReturnType<
          typeof usePortfolios
        >,
      );
    mockNetWorth({
      accounts: [],
      sections: [],
      netWorth: '0.00',
      totalAssets: '0.00',
      totalLiabilities: '0.00',
      approximate: false,
    });
    await renderOverview();
    expect(screen.getByText('Add an account to a portfolio to see figures.')).toBeTruthy();
    expect(screen.getByTestId('overview-net-worth').props.children).toMatch(/^CHF\s0\.00$/);
    expect(screen.queryByText('No figures yet')).toBeNull();
  });

  it('puts portfolios and cash flow in a 360 px right column from 1024 px', async () => {
    mockWindow.width = 1280;
    await renderOverview();
    const side = screen.getByTestId('overview-side-column');
    expect(StyleSheet.flatten(side.props.style).width).toBe(360);
    expect(screen.getByTestId('overview-columns')).toBeTruthy();
  });

  it('keeps the single-column phone order below 1024 px', async () => {
    mockWindow.width = 1023;
    await renderOverview();
    expect(screen.queryByTestId('overview-side-column')).toBeNull();
  });

  it('keeps the tablist accessible, with the selected first tab, portfolio and add tab', async () => {
    const add = jest.fn();
    await renderOverview('en', { onAddPortfolio: add });
    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(3);
    expect(tabs[0]).toBeSelected();
    expect(tabs[1]).not.toBeSelected();
    expect(tabs[2]).not.toBeSelected();
    await fireEvent.press(screen.getByRole('tab', { name: '+ Add portfolio' }));
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('shows and dismisses a safe notice when a portfolio deep link was removed', async () => {
    await renderOverview('en', { missingPortfolioNotice: true });
    expect(screen.getByText('This portfolio no longer exists.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('This portfolio no longer exists.')).toBeNull();
  });

  it('offers the web Import statement entry point for account selection', async () => {
    mockWindow.width = 1280;
    const openImport = jest.fn();
    await renderOverview('en', { onImportStatement: openImport });
    await fireEvent.press(screen.getByRole('button', { name: 'Import statement' }));
    expect(openImport).toHaveBeenCalledTimes(1);
  });

  it('leaves the page heading to the shell and shows only the greeting', async () => {
    await renderOverview();
    expect(screen.queryByRole('header', { name: 'Overview' })).toBeNull();
    expect(screen.getByText(/^Good (morning|afternoon|evening)$/)).toBeTruthy();
  });
});
