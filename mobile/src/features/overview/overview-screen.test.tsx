import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { ApiError } from '@/api/client';
import {
  useAccounts, useCashFlow, useNetWorth, usePortfolios, useWorkspace,
} from '@/api/queries/resources';
import type { components } from '@/api/schema';
import en from '@/i18n/en.json';
import de from '@/i18n/de.json';
import { OverviewScreen, type OverviewScreenProps } from './overview-screen';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/api/queries/resources', () => ({
  useWorkspace: jest.fn(), usePortfolios: jest.fn(), useNetWorth: jest.fn(),
  useAccounts: jest.fn(), useCashFlow: jest.fn(),
}));

type Workspace = components['schemas']['WorkspaceResponse'];
type Portfolio = components['schemas']['PortfolioListItemResponse'];
type NetWorth = components['schemas']['NetWorthResponse'];
type CashFlow = components['schemas']['CashFlowResponse'];

const workspace: Workspace = { id: 'workspace-1', name: 'Family Hug', currency: 'CHF', version: 3 };
const portfolio: Portfolio = {
  id: 'portfolio-1', version: 4, name: 'Euro Bank',
  catalogueInstitutionId: null, institutionType: 'BANK', country: 'DE', logoUrl: null,
  portfolioType: 'BANK', portfolioTypeOverridden: false, personalAssetsDefault: false,
  activeAccountCount: 2, portfolioCurrency: 'EUR', total: '2350.40',
  workspaceCurrency: 'CHF', totalInWorkspaceCurrency: '2244.33',
  complete: true, valueStatus: 'CURRENT', asOf: '2026-11-08', institutionName: null,
};
const accountValuation: NetWorth['accounts'][number] = {
  accountId: 'account-1', name: 'Mortgage', nature: 'LIABILITY',
  nativeCurrency: 'CHF', currency: 'CHF', value: '-400000.00',
  conversionRate: null, conversionRateDate: null, conversionRateCarriedForward: false,
  conversionRateStale: false, valueKnown: true, valueBasis: null,
  valueSourceDate: null, valueSourceStale: false, warnings: [],
};
const netWorth: NetWorth = {
  currency: 'CHF', asOf: '2026-11-08', totalAssets: '1079362.92',
  totalLiabilities: '-601202.36', netWorth: '478160.56',
  complete: true, approximate: true, warnings: [],
  accounts: [accountValuation],
  sections: [{
    section: 'CASH_AND_SAVINGS', subtotal: '45210.50',
    complete: true, valueStatus: 'CURRENT', accountCount: 2,
  }],
};
const cashFlow: CashFlow = {
  month: '2026-11', complete: true,
  income: [{ currency: 'CHF', amount: '5000.00', valueKnown: true,
    conversionRateStale: false, conversionRateCarriedForward: false }],
  spending: [{ currency: 'CHF', amount: '3000.00', valueKnown: true,
    conversionRateStale: false, conversionRateCarriedForward: false }],
  saving: [{ currency: 'CHF', amount: '2000.00', valueKnown: true,
    conversionRateStale: false, conversionRateCarriedForward: false }],
  pendingReview: [],
};

function success<T>(data: T) {
  return {
    data, dataUpdatedAt: 1794150000000, isPending: false, isSuccess: true, isError: false,
    isRefetching: false, error: null, refetch: jest.fn(async () => ({ data })),
  };
}
function failure(error: Error) {
  return {
    data: undefined, dataUpdatedAt: 0, isPending: false, isSuccess: false, isError: true,
    isRefetching: false, error, refetch: jest.fn(async () => ({ error })),
  };
}
function setFixtures() {
  jest.mocked(useWorkspace).mockReturnValue(success(workspace) as unknown as ReturnType<typeof useWorkspace>);
  jest.mocked(usePortfolios).mockReturnValue(success([portfolio]) as unknown as ReturnType<typeof usePortfolios>);
  jest.mocked(useNetWorth).mockReturnValue(success(netWorth) as unknown as ReturnType<typeof useNetWorth>);
  jest.mocked(useAccounts).mockReturnValue(success([]) as unknown as ReturnType<typeof useAccounts>);
  jest.mocked(useCashFlow).mockReturnValue(success(cashFlow) as unknown as ReturnType<typeof useCashFlow>);
}
async function renderOverview(
  locale: 'en' | 'de' = 'en',
  props: Partial<OverviewScreenProps> = {},
) {
  return render(
    <IntlProvider locale={locale} messages={locale === 'de' ? de : en}>
      <OverviewScreen onAddPortfolio={jest.fn()} {...props} />
    </IntlProvider>,
  );
}

describe('Overview S1 (#489)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    setFixtures();
  });

  it('shows the exact overview name, approximate net worth, section and converted portfolio amount', async () => {
    await renderOverview();
    expect(screen.getByRole('tab', { name: 'Family Hug' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Net worth.*478,160.56.*estimated/i })).toBeTruthy();
    expect(screen.getByText('Estimated')).toBeTruthy();
    expect(screen.getByText('Cash & savings')).toBeTruthy();
    expect(screen.getByText(/≈CHF/)).toBeTruthy();
    expect(screen.getByText('Euro Bank')).toBeTruthy();
  });

  it('falls back to the German tab label without exposing technical terminology', async () => {
    jest.mocked(useWorkspace).mockReturnValue(
      success({ ...workspace, name: null }) as unknown as ReturnType<typeof useWorkspace>,
    );
    await renderOverview('de');
    expect(screen.getByRole('tab', { name: 'Alle Portfolios' })).toBeTruthy();
  });

  it('keeps net worth and portfolios when cash flow fails, with its own retry action', async () => {
    jest.mocked(useCashFlow).mockReturnValue(
      failure(new ApiError('server error', 503)) as unknown as ReturnType<typeof useCashFlow>,
    );
    await renderOverview();
    expect(screen.getByRole('button', { name: /Net worth.*478,160.56/i })).toBeTruthy();
    expect(screen.getByText('Euro Bank')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Retry' }));
    expect(jest.mocked(useCashFlow).mock.results[0].value.refetch).toHaveBeenCalledTimes(1);
  });

  it('renders the first-run steps and starts the portfolio flow instead of guessing a zero value', async () => {
    jest.mocked(usePortfolios).mockReturnValue(success([]) as unknown as ReturnType<typeof usePortfolios>);
    jest.mocked(useNetWorth).mockReturnValue(
      success({ ...netWorth, accounts: [], sections: [] }) as unknown as ReturnType<typeof useNetWorth>,
    );
    const add = jest.fn();
    await renderOverview('en', { onAddPortfolio: add });
    expect(screen.getByText('No figures yet')).toBeTruthy();
    expect(screen.getByText(/about 10 minutes/)).toBeTruthy();
    expect(screen.queryByText('CHF\u00a0478,160.56')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Add your first portfolio' }));
    expect(add).toHaveBeenCalledTimes(1);
  });

  it('shows unknown-account names on the incomplete explanation sheet', async () => {
    jest.mocked(useNetWorth).mockReturnValue(success({
      ...netWorth, complete: false,
      accounts: [...netWorth.accounts, {
        ...accountValuation, accountId: 'unknown', name: 'Missing valuation',
        value: null, valueKnown: false,
      }],
    }) as unknown as ReturnType<typeof useNetWorth>);
    await renderOverview();
    await fireEvent.press(screen.getByText('Not all values are included'));
    expect(screen.getByText('Missing valuation')).toBeTruthy();
  });

  it('masks figures in the rendered and accessible output when privacy is enabled', async () => {
    const { toJSON } = await renderOverview('en', { hiddenAmounts: true });
    expect(screen.getAllByText('••••').length).toBeGreaterThan(2);
    const tree = JSON.stringify(toJSON());
    expect(tree).not.toContain('478,160.56');
    expect(tree).not.toContain('1,079,362.92');
    expect(tree).not.toContain('2,350.40');
  });

  it('changes only the selected currency for the two affected resource hooks', async () => {
    await renderOverview();
    await fireEvent.press(screen.getByRole('button', { name: 'Choose display currency' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'EUR' }));
    expect(useNetWorth).toHaveBeenLastCalledWith('EUR');
    expect(useCashFlow).toHaveBeenLastCalledWith(expect.any(String), 'EUR');
  });

  it('hides unsupported portfolio navigation until the Portfolio View is implemented', async () => {
    await renderOverview();
    expect(screen.queryByRole('tab', { name: 'Euro Bank' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Euro Bank/ })).toBeNull();
  });
});
