import { accountRows, accountTypeMessageKey, daysUntil, hasPositions,
  isNegative, sectionMessageKey, typeMessageKey, type PortfolioAccount,
  type PortfolioSection } from './model';

const account = (id: string, linkedPropertyAccountId: string | null = null) =>
  ({ accountId: id, linkedPropertyAccountId, positionsCount: null }) as PortfolioAccount;
const section = (type: string, accounts: PortfolioAccount[]) => ({
  section: type, accounts,
}) as PortfolioSection;

describe('Portfolio View S2 (#493) model', () => {
  it('respects the backend sections, including an unknown future section', () => {
    expect(sectionMessageKey('CASH_AND_SAVINGS')).toBe('portfolio.section.cash');
    expect(sectionMessageKey('CARDS')).toBe('portfolio.section.cards');
    expect(sectionMessageKey('FUTURE')).toBe('portfolio.section.other');
    expect(typeMessageKey('MIXED')).toBe('portfolio.type.mixed');
    expect(typeMessageKey('NEW_SERVER_TYPE')).toBeNull();
    expect(accountTypeMessageKey('FUTURE')).toBe('portfolio.account.other');
  });

  it('does not clamp negative portfolio totals, including decimals', () => {
    expect(isNegative('-8400.25')).toBe(true);
    expect(isNegative('-0.02')).toBe(true);
    expect(isNegative('-0.00')).toBe(false);
    expect(isNegative('0.00')).toBe(false);
    expect(isNegative('100.00')).toBe(false);
  });

  it('calculates due dates as calendar days, not elapsed hours', () => {
    expect(daysUntil('2026-11-17', new Date(2026, 10, 9, 23, 58))).toBe(8);
    expect(daysUntil('2026-11-09', new Date(2026, 10, 9, 0, 1))).toBe(0);
    expect(daysUntil('2026-11-01', new Date(2026, 10, 9))).toBe(-8);
    expect(daysUntil('2026-02-30', new Date(2026, 1, 1))).toBeNull();
    expect(daysUntil('garbage', new Date())).toBeNull();
  });

  it('nests mortgages below their linked property without changing unrelated order', () => {
    const rows = [account('mortgage', 'house'), account('cash'), account('house'),
      account('unlinked', 'removed'), account('second-house')];
    expect(accountRows(section('PROPERTY_AND_MORTGAGE', rows)).map(a => a.accountId))
      .toEqual(['cash', 'house', 'mortgage', 'unlinked', 'second-house']);
    expect(accountRows(section('CARDS', rows)).map(a => a.accountId))
      .toEqual(rows.map(a => a.accountId));
  });

  it('only requests positions for sections and accounts with positions', () => {
    expect(hasPositions({ ...account('depot'), positionsCount: 3 },
      section('DEPOT', []))).toBe(true);
    expect(hasPositions({ ...account('depot'), positionsCount: 3 },
      section('CRYPTO', []))).toBe(true);
    expect(hasPositions({ ...account('cash'), positionsCount: 3 },
      section('CASH_AND_SAVINGS', []))).toBe(false);
    expect(hasPositions({ ...account('depot'), positionsCount: null },
      section('DEPOT', []))).toBe(false);
  });
});
