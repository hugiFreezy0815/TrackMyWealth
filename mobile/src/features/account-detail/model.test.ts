import type { Transaction, Category, AccountSummary, AccountListItem } from './model';
import { accountValuation, categoryFor, categoryName, groupedTransactions, isUncategorized, uniqueTransactions } from './model';

const row = (id: string, bookingDate = '2026-11-09', other: Partial<Transaction> = {}): Transaction =>
  ({ id, bookingDate, categoryId: null, internalTransfer: false, ...other }) as Transaction;

const categories: Category[] = [
  { id: 'uncategorized', code: 'UNCATEGORIZED', nameEn: 'Uncategorised', nameDe: 'Nicht kategorisiert', parentId: null },
  { id: 'food', code: 'FOOD', nameEn: 'Food', nameDe: 'Essen', parentId: null },
  { id: 'groceries', code: 'GROCERIES', nameEn: 'Groceries', nameDe: 'Lebensmittel', parentId: 'food' },
] as Category[];

describe('S3 account detail view model', () => {
  it('deduplicates overlapping pages while preserving server order', () => {
    expect(uniqueTransactions([
      { content: [row('a'), row('b')] },
      { content: [row('b'), row('c')] },
    ]).map((transaction) => transaction.id)).toEqual(['a', 'b', 'c']);
  });

  it('groups only adjacent dates and never invents amounts or chronology', () => {
    const input = [row('a', '2026-11-09'), row('b', '2026-11-09'), row('c', '2026-11-08')];
    expect(groupedTransactions(input, true).map((group) => [group.date, group.rows.length]))
      .toEqual([['2026-11-09', 2], ['2026-11-08', 1]]);
    expect(groupedTransactions(input, false)).toEqual([{ date: null, rows: input }]);
  });

  it('only treats the shipped default as uncategorized, not null or a transfer', () => {
    expect(isUncategorized(row('a', '2026-11-09', { categoryId: 'uncategorized' }), categories)).toBe(true);
    expect(isUncategorized(row('b'), categories)).toBe(false);
    expect(isUncategorized(row('c', '2026-11-09', {
      categoryId: 'uncategorized', internalTransfer: true,
    }), categories)).toBe(false);
    expect(categoryFor(row('d', '2026-11-09', { categoryId: 'groceries' }), categories)?.code).toBe('GROCERIES');
  });

  it('uses the category hierarchy and locale supplied by the server', () => {
    expect(categoryName(categories[2], categories, false, true)).toBe('Food › Groceries');
    expect(categoryName(categories[2], categories, true, true)).toBe('Essen › Lebensmittel');
  });

  it('never uses an active valuation for an archived account', () => {
    const summary = { id: 'a', status: 'ARCHIVED' } as AccountSummary;
    const active = [{ id: 'a', balance: '20.00' }] as AccountListItem[];
    const archived = [{ id: 'a', balance: '10.00' }] as AccountListItem[];
    expect(accountValuation(summary, active, archived)?.balance).toBe('10.00');
    expect(accountValuation(summary, active, undefined)).toBeUndefined();
  });
});
