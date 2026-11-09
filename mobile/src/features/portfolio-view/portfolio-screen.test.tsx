import { fireEvent, render, screen } from '@testing-library/react-native';
import { IntlProvider } from 'react-intl';

import { ApiError } from '@/api/client';
import { useAccountPositions, usePortfolios, usePortfolioView } from '@/api/queries/resources';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import en from '@/i18n/en.json';
import de from '@/i18n/de.json';
import { PortfolioScreen } from './portfolio-screen';
import type { PortfolioAccount, PortfolioSection, PortfolioView } from './model';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@/features/privacy/amount-visibility', () => ({ useAmountsHidden: jest.fn(() => false) }));
jest.mock('@/api/queries/resources', () => ({
  usePortfolios: jest.fn(), usePortfolioView: jest.fn(), useAccountPositions: jest.fn(),
}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({
    textPrimary: '#0B1C3F', textSecondary: '#53627A', textTertiary: '#53627A',
    bgSurface: '#FFFFFF', bgSubtle: '#ECF1F4', bgCanvas: '#F8FAFC',
    bgInverse: '#0B1C3F', brandLink: '#006D64', brandPrimary: '#00857A',
    statusGain: '#007D55', statusLoss: '#C42F37', statusWarning: '#8F5200',
    statusInfo: '#006EA6', statusInfoSubtle: '#E7F4F9',
    statusWarningSubtle: '#FFF4E5', statusLossSubtle: '#FCE9E9',
    borderInteractive: '#64758A', borderDefault: '#E1E7EC', textOnBrand: '#FFFFFF',
  }),
}));
const makeAccount = (id: string, overrides: Partial<PortfolioAccount> = {}): PortfolioAccount => ({
  accountId: id, name: id, accountType: 'CASH', nature: 'ASSET',
  currency: 'EUR', balance: '100.00', valueInPortfolioCurrency: '100.00',
  valueKnown: true, valueStatus: 'CURRENT', valueSourceDate: '2026-09-14',
  warnings: [], positionsCount: null, positionsAsOf: null, totalReturn: null,
  dueDate: null, linkedPropertyAccountId: null, maskedIdentifier: null,
  reconciliationStatus: 'UNKNOWN', statementDate: null, ...overrides,
});
const makeSection = (name: PortfolioSection['section'], accounts: PortfolioAccount[],
  subtotal = '100.00'): PortfolioSection => ({
  section: name, subtotal, subtotalInWorkspaceCurrency: subtotal,
  complete: true, valueStatus: 'CURRENT', accounts,
});
const view = (sections: PortfolioSection[], overrides: Partial<PortfolioView> = {}): PortfolioView => ({
  portfolioId: 'p-1', name: 'Home', portfolioType: 'MIXED',
  portfolioTypeOverridden: false, personalAssetsDefault: false,
  portfolioCurrency: 'EUR', workspaceCurrency: 'CHF', asOf: '2026-11-09',
  total: '-200.00', totalInWorkspaceCurrency: '-190.00',
  complete: true, valueStatus: 'CURRENT', warnings: [], sections,
  institutionName: null, logoUrl: null, conversionRate: null,
  conversionRateDate: null, conversionRateCarriedForward: false,
  conversionRateStale: false, lastImport: null, ...overrides,
});

function setData(data: PortfolioView | undefined, error?: Error) {
  jest.mocked(usePortfolios).mockReturnValue({
    data: [{ id: 'p-1', name: 'Home', total: '-200.00', portfolioCurrency: 'EUR',
      workspaceCurrency: 'CHF', totalInWorkspaceCurrency: '-190.00', complete: true,
      valueStatus: 'CURRENT', portfolioType: 'MIXED', personalAssetsDefault: false,
      asOf: '2026-11-09', institutionName: null, logoUrl: null }],
    isRefetching: false, refetch: jest.fn(),
  } as unknown as ReturnType<typeof usePortfolios>);
  jest.mocked(usePortfolioView).mockReturnValue({
    data, error: error ?? null, isPending: !data && !error,
    isError: !!error, isRefetching: false, refetch: jest.fn(),
    dataUpdatedAt: 1794200000000,
  } as unknown as ReturnType<typeof usePortfolioView>);
  jest.mocked(useAccountPositions).mockReturnValue({
    data: { accountId: 'depot', currency: 'EUR', asOf: '2026-09-30',
      source: 'HOLDINGS_SNAPSHOT', complete: true, positions: [] },
    isPending: false, isError: false, refetch: jest.fn(),
  } as unknown as ReturnType<typeof useAccountPositions>);
}
async function mount(locale: 'en' | 'de' = 'en') {
  const onAccount = jest.fn(), onPortfolio = jest.fn(), onAddAccount = jest.fn();
  const onMissing = jest.fn();
  const result = await render(
    <IntlProvider locale={locale} messages={locale === 'de' ? de : en}>
      <PortfolioScreen portfolioId="p-1" overviewName="All portfolios"
        onOverview={jest.fn()} onPortfolio={onPortfolio} onAddPortfolio={jest.fn()}
        onAccount={onAccount} onAddAccount={onAddAccount}
        onImport={jest.fn()} onMissing={onMissing} />
    </IntlProvider>,
  );
  return { ...result, onAccount, onPortfolio, onAddAccount, onMissing };
}
beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(useAmountsHidden).mockReturnValue(false);
});

describe('S2 Portfolio View acceptance (#493)', () => {
  it('renders exactly the sections in server order and an unknown section as Other', async () => {
    setData(view([
      makeSection('CASH_AND_SAVINGS', [makeAccount('Girokonto')]),
      makeSection('CARDS', [makeAccount('Visa', { accountType: 'CREDIT_CARD',
        balance: '-12.00' })], '-12.00'),
    ]));
    await mount();
    expect(screen.getByText('Cash & savings')).toBeTruthy();
    expect(screen.getByText('Credit card')).toBeTruthy();
    expect(screen.queryByText('Depot')).toBeNull();
    expect(screen.queryByText('Pillar 3a')).toBeNull();
    expect(screen.getByText('Girokonto')).toBeTruthy();
    expect(screen.getByText('Visa')).toBeTruthy();
  });

  it('preserves minus sign and accessibility for a negative mortgage total', async () => {
    setData(view([makeSection('PROPERTY_AND_MORTGAGE', [
      makeAccount('Mortgage', { accountType: 'MORTGAGE', nature: 'LIABILITY', balance: '-600.00' }),
    ], '-600.00')]));
    await mount();
    const amount = screen.getByTestId('portfolio-total');
    expect(amount.props.children).toContain('−');
    expect(amount.props.accessibilityLabel).toMatch(/minus/i);
    expect(screen.getByRole('button', { name: /Home total/ })).toBeTruthy();
  });

  it('renders stale source date and card due date from API data', async () => {
    const due = new Date();
    due.setDate(due.getDate() + 8);
    const iso = [due.getFullYear(), String(due.getMonth() + 1).padStart(2, '0'),
      String(due.getDate()).padStart(2, '0')].join('-');
    setData(view([
      makeSection('CASH_AND_SAVINGS', [makeAccount('Savings', {
        valueStatus: 'STALE', valueSourceDate: '2026-09-14',
      })]),
      makeSection('CARDS', [makeAccount('Visa', { accountType: 'CREDIT_CARD',
        dueDate: iso, balance: '-12.00' })]),
    ]));
    await mount();
    expect(screen.getAllByText(/Price from 14 Sep/).length).toBeGreaterThan(0);
    expect(screen.getByText('Due in 8 days')).toBeTruthy();
    expect(screen.getByText('Savings')).toBeTruthy();
  });

  it('supports empty portfolios and Add account without inventing a balance', async () => {
    setData(view([], { total: '0.00', totalInWorkspaceCurrency: '0.00' }));
    const { onAddAccount } = await mount('de');
    expect(screen.getByText('Noch keine Konten in Home')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Konto hinzufügen' }));
    expect(onAddAccount).toHaveBeenCalledWith('p-1');
  });

  it('keeps cached header and offers Retry on view errors', async () => {
    setData(undefined, new Error('network failed'));
    await mount();
    expect(screen.getByText('Home')).toBeTruthy();
    expect(screen.getByTestId('portfolio-total')).toBeTruthy();
  });

  it('does not silently redirect a removed portfolio; offers a safe back action', async () => {
    setData(undefined, new ApiError('not found', 404, { code: 'NOT_FOUND' }));
    const { onMissing } = await mount();
    expect(screen.getByText('This portfolio no longer exists.')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'All portfolios' }));
    expect(onMissing).toHaveBeenCalledTimes(1);
  });
});
