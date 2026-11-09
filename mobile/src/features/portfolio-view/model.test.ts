import {
  accountRows,
  accountTypeMessageKey,
  daysUntil,
  hasPositions,
  sectionMessageKey,
  typeMessageKey,
  unknownAccounts,
  type PortfolioAccount,
  type PortfolioSection,
} from './model';

const account = (
  id: string,
  linkedPropertyAccountId: string | null = null,
  overrides: Partial<PortfolioAccount> = {},
) =>
  ({
    accountId: id,
    linkedPropertyAccountId,
    positionsCount: null,
    valueKnown: true,
    valueStatus: 'CURRENT',
    ...overrides,
  }) as PortfolioAccount;
const section = (type: string, accounts: PortfolioAccount[]) =>
  ({ section: type, accounts }) as PortfolioSection;
const ids = (entries: ReturnType<typeof accountRows>) =>
  entries.map(({ account: row, nested }) => `${row.accountId}${nested ? ' (nested)' : ''}`);

beforeEach(() => {
  // labelFor warns once per unknown enum value in development; keep the test output clean.
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});
afterEach(() => jest.restoreAllMocks());

describe('Portfolio View S2 (#493) model', () => {
  it('respects the backend sections, including an unknown future section', () => {
    expect(sectionMessageKey('CASH_AND_SAVINGS')).toBe('portfolio.section.cash');
    expect(sectionMessageKey('CARDS')).toBe('portfolio.section.cards');
    expect(sectionMessageKey('FUTURE')).toBe('portfolio.section.other');
    // Own keys only: a prototype member is not a section label.
    expect(sectionMessageKey('toString')).toBe('portfolio.section.other');
    expect(typeMessageKey('MIXED')).toBe('portfolio.type.mixed');
    expect(typeMessageKey('NEW_SERVER_TYPE')).toBeNull();
    expect(accountTypeMessageKey('FUTURE')).toBe('portfolio.account.other');
  });

  it('calculates due dates as calendar days, not elapsed hours', () => {
    expect(daysUntil('2026-11-17', new Date(2026, 10, 9, 23, 58))).toBe(8);
    expect(daysUntil('2026-11-09', new Date(2026, 10, 9, 0, 1))).toBe(0);
    expect(daysUntil('2026-11-01', new Date(2026, 10, 9))).toBe(-8);
    // Across the DST change (29 Mar 2026 in Europe) the count stays in whole days.
    expect(daysUntil('2026-03-30', new Date(2026, 2, 28, 12))).toBe(2);
    expect(daysUntil('2026-02-30', new Date(2026, 1, 1))).toBeNull();
    expect(daysUntil('garbage', new Date())).toBeNull();
  });

  it('nests mortgages below their linked property without changing unrelated order', () => {
    const rows = [
      account('mortgage', 'house'),
      account('cash'),
      account('house'),
      account('unlinked', 'removed'),
      account('second-house'),
    ];
    expect(ids(accountRows(section('PROPERTY_AND_MORTGAGE', rows)))).toEqual([
      'cash',
      'house',
      'mortgage (nested)',
      // Its property is not in the section: shown in place and not indented.
      'unlinked',
      'second-house',
    ]);
    expect(ids(accountRows(section('CARDS', rows)))).toEqual(rows.map((row) => row.accountId));
  });

  it('keeps a row that links to itself instead of dropping it', () => {
    expect(ids(accountRows(section('PROPERTY_AND_MORTGAGE', [account('loop', 'loop')])))).toEqual([
      'loop',
    ]);
  });

  it('only requests positions for sections and accounts with positions', () => {
    const depot = account('depot', null, { positionsCount: 3 });
    expect(hasPositions(depot, section('DEPOT', []))).toBe(true);
    expect(hasPositions(depot, section('CRYPTO', []))).toBe(true);
    expect(hasPositions(depot, section('CASH_AND_SAVINGS', []))).toBe(false);
    expect(hasPositions(account('empty', null, { positionsCount: 0 }), section('DEPOT', []))).toBe(
      false,
    );
    expect(hasPositions(account('depot'), section('DEPOT', []))).toBe(false);
  });

  it('names the accounts whose value is unknown for the explanation sheet', () => {
    const sections = [
      section('CASH_AND_SAVINGS', [
        account('known'),
        account('unknown', null, { valueKnown: false }),
      ]),
      section('DEPOT', [account('unpriced', null, { valueStatus: 'UNKNOWN' })]),
    ];
    expect(unknownAccounts(sections).map((row) => row.accountId)).toEqual(['unknown', 'unpriced']);
  });
});
