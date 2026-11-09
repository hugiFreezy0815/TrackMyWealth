import type { components } from '@/api/schema';
import {
  barWeights,
  compareMagnitude,
  dataWarningMessageIds,
  isNegativeAmount,
  currencyAmount,
  hasNonZeroAmount,
  isFirstRunEmpty,
  largestLiabilities,
  monthKey,
  scopeCurrencies,
  unknownValuations,
  unreconciledAccounts,
  type Account,
  type NetWorth,
  type Portfolio,
} from './overview-model';

const valuation = (
  accountId: string,
  value: string | null,
  valueKnown = true,
): NetWorth['accounts'][number] => ({
  accountId,
  name: accountId,
  nature: 'LIABILITY',
  nativeCurrency: 'CHF',
  currency: 'CHF',
  value,
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  valueKnown,
  valueBasis: null,
  valueSourceDate: null,
  valueSourceStale: false,
  warnings: [],
});
const netWorth = (accounts: NetWorth['accounts'] = []): NetWorth => ({
  currency: 'CHF',
  asOf: '2026-11-09',
  totalAssets: '0',
  totalLiabilities: '0',
  netWorth: '0',
  complete: true,
  approximate: false,
  warnings: [],
  sections: [],
  accounts,
});
const portfolio = (count: number, personalAssetsDefault = false) =>
  ({ id: 'portfolio', activeAccountCount: count, personalAssetsDefault }) as Portfolio;

describe('overview server-driven selectors (#489)', () => {
  it('shows first-run only with no financial accounts and no active user portfolio', () => {
    expect(isFirstRunEmpty([], netWorth())).toBe(true);
    expect(isFirstRunEmpty([portfolio(0, true)], netWorth())).toBe(true);
    expect(isFirstRunEmpty([portfolio(0)], netWorth())).toBe(false);
    expect(isFirstRunEmpty([portfolio(1, true)], netWorth())).toBe(false);
    expect(isFirstRunEmpty([portfolio(0, true)], netWorth([valuation('x', '0')]))).toBe(false);
  });

  it('preserves enormous decimal precision when ordering liability rows', () => {
    expect(
      compareMagnitude('-12345678901234567890.45', '-12345678901234567890.44'),
    ).toBeGreaterThan(0);
    expect(compareMagnitude('9.9', '10')).toBeLessThan(0);
    expect(compareMagnitude('-0008.500', '8.5')).toBe(0);
    const data = netWorth([
      valuation('small', '-50'),
      valuation('unknown', null, false),
      valuation('large', '-12345678901234567890.45'),
      valuation('medium', '-600'),
    ]);
    expect(largestLiabilities(data).map((row) => row.accountId)).toEqual([
      'large',
      'medium',
      'small',
    ]);
  });

  it('never replaces an unknown value with zero or aggregates a financial amount', () => {
    const data = netWorth([valuation('unknown', null, false), valuation('present', '0')]);
    expect(unknownValuations(data).map((row) => row.accountId)).toEqual(['unknown']);
  });

  it('identifies unreconciled, non-archived accounts only', () => {
    const item = (id: string, status: string, archived: boolean) =>
      ({
        id,
        valueStatus: status,
        archived,
      }) as Account;
    expect(
      unreconciledAccounts([
        item('first', 'UNRECONCILED', false),
        item('archived', 'UNRECONCILED', true),
        item('healthy', 'CURRENT', false),
      ]).map((row) => row.id),
    ).toEqual(['first']);
  });

  it('selects cash-flow entries by server currency and hides zero pending amounts', () => {
    const entries: components['schemas']['CurrencyAmount'][] = [
      {
        currency: 'EUR',
        amount: '12.01',
        valueKnown: true,
        conversionRateCarriedForward: false,
        conversionRateStale: false,
      },
      {
        currency: 'CHF',
        amount: null,
        valueKnown: false,
        conversionRateCarriedForward: false,
        conversionRateStale: false,
      },
    ];
    expect(currencyAmount(entries, 'EUR')?.amount).toBe('12.01');
    expect(currencyAmount(entries, 'CHF')?.valueKnown).toBe(false);
    expect(currencyAmount(entries, 'USD')).toBeUndefined();
    expect(hasNonZeroAmount('0.00000')).toBe(false);
    expect(hasNonZeroAmount('-0.00')).toBe(false);
    expect(hasNonZeroAmount('0.0001')).toBe(true);
    expect(hasNonZeroAmount(null)).toBe(false);
  });

  it('keeps workspace currency first and accepts local calendar months', () => {
    expect(scopeCurrencies('EUR')).toEqual(['EUR', 'CHF', 'USD', 'GBP']);
    expect(monthKey(new Date(2026, 0, 1))).toBe('2026-01');
  });

  it('tells a negative server amount from zero and positive values without arithmetic', () => {
    expect(isNegativeAmount('-0.01')).toBe(true);
    expect(isNegativeAmount('-123456789012345678901234567890.10')).toBe(true);
    expect(isNegativeAmount('-0.00')).toBe(false);
    expect(isNegativeAmount('0')).toBe(false);
    expect(isNegativeAmount('478160.56')).toBe(false);
    expect(isNegativeAmount(null)).toBe(false);
  });

  it('sizes the decorative bar from the magnitudes and hides it when both are zero', () => {
    expect(barWeights('1079362.92', '-601202.36')).toEqual({
      assets: 1079362.92,
      liabilities: 601202.36,
    });
    expect(barWeights('100.00', '0.00')).toEqual({ assets: 100, liabilities: 0 });
    expect(barWeights('0.00', '-0.00')).toBeNull();
    expect(barWeights('not-a-number', '0')).toBeNull();
  });

  it('maps data-quality warning codes to messages and collapses unknown codes', () => {
    expect(
      dataWarningMessageIds([
        'OPEN_RECONCILIATION_DIFFERENCE',
        'FUTURE_CODE',
        'ANOTHER_FUTURE_CODE',
        'OPEN_RECONCILIATION_DIFFERENCE',
        'toString',
      ]),
    ).toEqual([
      'overview.dataWarning.OPEN_RECONCILIATION_DIFFERENCE',
      'overview.dataWarning.other',
    ]);
    expect(dataWarningMessageIds([])).toEqual([]);
  });
});
