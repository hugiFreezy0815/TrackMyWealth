import { createIntl, createIntlCache } from 'react-intl';

import de from '@/i18n/de.json';
import en from '@/i18n/en.json';

import type { AccountListItem, AccountSummary, Category, RowContext, Transaction } from './model';
import {
  accountTypeLabel,
  accountValuation,
  asOfLine,
  categoryFor,
  categoryName,
  convertedAmount,
  groupHeading,
  groupedTransactions,
  hasUnknownCategory,
  isUncategorized,
  presentTransaction,
  reconciliationLine,
  reversalOf,
  transactionTypeLabel,
  uniqueTransactions,
  valueSource,
} from './model';

const enIntl = createIntl({ locale: 'en', messages: en }, createIntlCache());
const deIntl = createIntl({ locale: 'de-CH', messages: { ...en, ...de } }, createIntlCache());

const row = (
  id: string,
  bookingDate = '2026-11-09',
  other: Partial<Transaction> = {},
): Transaction =>
  ({
    id,
    bookingDate,
    transactionType: 'EXPENSE',
    amount: '-10.00',
    currency: 'EUR',
    merchantDescription: `Shop ${id}`,
    counterpartyName: null,
    categoryId: null,
    internalTransfer: false,
    voidedAt: null,
    replacesTransactionId: null,
    correctsTransactionId: null,
    reconciliationAdjustment: null,
    amountInAccountCurrency: null,
    fxRateEstimated: false,
    warnings: [],
    version: 0,
    ...other,
  }) as Transaction;

const categories: Category[] = [
  {
    id: 'uncategorized',
    code: 'UNCATEGORIZED',
    nameEn: 'Uncategorised',
    nameDe: 'Nicht kategorisiert',
    parentId: null,
  },
  { id: 'food', code: 'FOOD', nameEn: 'Food', nameDe: 'Essen', parentId: null },
  {
    id: 'groceries',
    code: 'GROCERIES',
    nameEn: 'Groceries',
    nameDe: 'Lebensmittel',
    parentId: 'food',
  },
] as Category[];

const context = (other: Partial<RowContext> = {}): RowContext => ({
  categories,
  loaded: [],
  accountCurrency: 'EUR',
  archived: false,
  includeParent: false,
  ...other,
});

// Keep the financial grouping and filter logic independent of the UI renderer.
describe('S3 account detail view model', () => {
  it('deduplicates overlapping pages while preserving server order', () => {
    expect(
      uniqueTransactions([
        { content: [row('a'), row('b')] },
        { content: [row('b'), row('c')] },
      ]).map((transaction) => transaction.id),
    ).toEqual(['a', 'b', 'c']);
  });

  it('groups only adjacent dates and never invents amounts or chronology', () => {
    const input = [row('a', '2026-11-09'), row('b', '2026-11-09'), row('c', '2026-11-08')];
    expect(
      groupedTransactions(input, true).map((group) => [group.date, group.rows.length]),
    ).toEqual([
      ['2026-11-09', 2],
      ['2026-11-08', 1],
    ]);
    expect(groupedTransactions(input, false)).toEqual([{ date: null, rows: input }]);
  });

  it('heads groups "Today · date", "Yesterday · date", else the date alone', () => {
    const today = new Date(2026, 9, 20, 12, 0);
    expect(groupHeading('2026-10-20', enIntl, today)).toBe('Today · 20 Oct 2026');
    expect(groupHeading('2026-10-19', deIntl, today)).toBe('Gestern · 19.10.2026');
    expect(groupHeading('2026-10-18', enIntl, today)).toBe('18 Oct 2026');
  });

  it('only treats the shipped default as uncategorized, not null or a transfer', () => {
    expect(
      isUncategorized(row('a', '2026-11-09', { categoryId: 'uncategorized' }), categories),
    ).toBe(true);
    expect(isUncategorized(row('b'), categories)).toBe(false);
    expect(
      isUncategorized(
        row('c', '2026-11-09', { categoryId: 'uncategorized', internalTransfer: true }),
        categories,
      ),
    ).toBe(false);
    expect(categoryFor(row('d', '2026-11-09', { categoryId: 'groceries' }), categories)?.code).toBe(
      'GROCERIES',
    );
  });

  it('uses the category hierarchy and locale supplied by the server', () => {
    expect(categoryName(categories[2], categories, false, true)).toBe('Food › Groceries');
    expect(categoryName(categories[2], categories, true, true)).toBe('Essen › Lebensmittel');
  });

  it('detects a category id missing from the cached list, ignoring null and transfers', () => {
    expect(hasUnknownCategory([row('a', '2026-11-09', { categoryId: 'new' })], categories)).toBe(
      true,
    );
    expect(
      hasUnknownCategory(
        [row('b'), row('c', '2026-11-09', { categoryId: 'new', internalTransfer: true })],
        categories,
      ),
    ).toBe(false);
  });

  it('never uses an active valuation for an archived account', () => {
    const summary = { id: 'a', status: 'ARCHIVED' } as AccountSummary;
    const active = [{ id: 'a', balance: '20.00' }] as AccountListItem[];
    const archived = [{ id: 'a', balance: '10.00' }] as AccountListItem[];
    expect(accountValuation(summary, active, archived)?.balance).toBe('10.00');
    expect(accountValuation(summary, active, undefined)).toBeUndefined();
  });

  it('labels API codes from the catalogue and an unknown code as "Other"', () => {
    jest.spyOn(console, 'warn').mockImplementation(() => {});
    expect(transactionTypeLabel('CREDIT_CARD_PURCHASE', enIntl)).toBe('Card payment');
    expect(transactionTypeLabel('CREDIT_CARD_PURCHASE', deIntl)).toBe('Kartenzahlung');
    expect(transactionTypeLabel('SOMETHING_NEW', enIntl)).toBe('Other');
    expect(accountTypeLabel('SAVINGS', deIntl)).toBe('Sparkonto');
    expect(accountTypeLabel('toString', enIntl)).toBe('Other');
  });

  it('derives the value source from capabilities and words the as-of line by it', () => {
    const value = { asOf: '2026-10-20', valueSourceDate: '2026-09-30' };
    expect(valueSource({ manualValuation: true, hasTransactions: false })).toBe('MANUAL_VALUATION');
    expect(valueSource({ manualValuation: false, hasTransactions: true })).toBe('LEDGER');
    expect(valueSource({ manualValuation: false, hasTransactions: false })).toBe('LATEST_SNAPSHOT');
    expect(asOfLine('LATEST_SNAPSHOT', value, enIntl)).toBe('Statement of 30 Sep 2026');
    expect(asOfLine('LEDGER', value, enIntl)).toBe('As of 20 Oct 2026');
    expect(asOfLine('MANUAL_VALUATION', { ...value, valueSourceDate: null }, enIntl)).toBe(
      'As of 20 Oct 2026',
    );
  });

  describe('reconciliation line (FR-REC)', () => {
    const base = { reason: null, asOf: '2026-09-30', openDifference: null, currency: null };

    it('pairs a warning colour with an icon and carries the difference', () => {
      expect(
        reconciliationLine({
          ...base,
          status: 'OPEN_DIFFERENCE',
          openDifference: '12.50',
          currency: 'EUR',
        }),
      ).toEqual({
        messageId: 'account.difference',
        tone: 'warning',
        icon: '⚠',
        date: '2026-09-30',
        difference: { value: '12.50', currency: 'EUR' },
      });
    });

    it('shows the accepted difference with its amount and date', () => {
      expect(
        reconciliationLine({
          ...base,
          status: 'DISMISSED_DIFFERENCE',
          openDifference: '3.00',
          currency: 'CHF',
        }),
      ).toMatchObject({
        messageId: 'account.dismissedDifference',
        difference: { value: '3.00', currency: 'CHF' },
      });
    });

    it('marks a reconciled account with an icon and never carries an amount', () => {
      expect(reconciliationLine({ ...base, status: 'RECONCILED' })).toMatchObject({
        icon: '✓',
        tone: 'success',
        difference: null,
      });
    });

    it.each(['NOT_RECONCILABLE', 'CASH_SCOPE_NOT_APPLICABLE', 'SOMETHING_NEW', 'toString'])(
      'hides the line for %s',
      (status) => {
        expect(reconciliationLine({ ...base, status })).toBeNull();
      },
    );
  });

  it('finds the reversed original among the loaded rows only', () => {
    const original = row('orig', '2026-10-01');
    const reversal = row('rev', '2026-10-05', { replacesTransactionId: 'orig' });
    expect(reversalOf(reversal, [original, reversal])).toBe('2026-10-01');
    expect(reversalOf(reversal, [reversal])).toBeNull();
    expect(reversalOf(original, [original])).toBeNull();
  });

  it('converts only a booking in another currency', () => {
    expect(
      convertedAmount(
        row('a', '2026-11-09', {
          currency: 'USD',
          amountInAccountCurrency: '-39.99',
          fxRateEstimated: true,
        }),
        'EUR',
      ),
    ).toEqual({ value: '-39.99', currency: 'EUR', estimated: true });
    expect(convertedAmount(row('b', '2026-11-09', { amountInAccountCurrency: '-10' }), 'EUR')).toBe(
      null,
    );
  });

  describe('row presentation', () => {
    it('shows an internal transfer as excluded, never as uncategorised', () => {
      const view = presentTransaction(
        row('t', '2026-11-09', {
          transactionType: 'TRANSFER',
          internalTransfer: true,
          categoryId: 'uncategorized',
        }),
        context(),
        enIntl,
      );
      expect(view.categoryLabel).toBe('Internal transfer · excluded');
      expect(view.uncategorized).toBe(false);
      expect(view.canCategorize).toBe(false);
    });

    it('labels the shipped default with the app word, whatever the server calls it', () => {
      const server = categories.map((category) =>
        category.code === 'UNCATEGORIZED' ? { ...category, nameEn: 'Uncategorized' } : category,
      );
      const row0 = row('u', '2026-11-09', { categoryId: 'uncategorized' });
      expect(presentTransaction(row0, context({ categories: server }), enIntl)).toMatchObject({
        categoryLabel: 'Uncategorised',
        uncategorized: true,
        canCategorize: true,
      });
      expect(presentTransaction(row0, context(), deIntl).categoryLabel).toBe('Nicht kategorisiert');
    });

    it('gives a dividend (categoryId null) no chip', () => {
      const view = presentTransaction(
        row('d', '2026-11-09', { transactionType: 'DIVIDEND', merchantDescription: null }),
        context(),
        enIntl,
      );
      expect(view.categoryLabel).toBeNull();
      expect(view.canCategorize).toBe(false);
      expect(view.title).toBe('Dividend');
    });

    it('marks a voided original and dates its reversal from the loaded rows', () => {
      const original = row('o', '2026-10-01', { voidedAt: '2026-10-05T08:00:00Z' });
      const reversal = row('r', '2026-10-05', { replacesTransactionId: 'o' });
      const loaded = context({ loaded: [reversal, original] });
      expect(presentTransaction(original, loaded, enIntl)).toMatchObject({
        voided: true,
        canCategorize: false,
      });
      expect(presentTransaction(reversal, loaded, enIntl).lifecycle).toEqual([
        'Reversal of 1 Oct 2026',
      ]);
      expect(presentTransaction(reversal, context(), enIntl).lifecycle).toEqual(['Reversal']);
    });

    it('titles a reconciliation adjustment "Statement adjustment" and locks its category', () => {
      const view = presentTransaction(
        row('a', '2026-11-09', {
          transactionType: 'VALUATION_ADJUSTMENT',
          merchantDescription: null,
          reconciliationAdjustment: 'STATEMENT',
          categoryId: 'food',
        }),
        context(),
        enIntl,
      );
      expect(view.title).toBe('Statement adjustment');
      expect(view.canCategorize).toBe(false);
    });

    it('falls back to "Category" for an id the list lacks, and keeps it changeable', () => {
      const view = presentTransaction(
        row('x', '2026-11-09', { categoryId: 'new' }),
        context(),
        enIntl,
      );
      expect(view.categoryLabel).toBe('Category');
      expect(view.canCategorize).toBe(true);
    });

    it('names the opening-balance warning and collapses unknown codes', () => {
      const view = presentTransaction(
        row('w', '2026-11-09', {
          warnings: ['BOOKED_BEFORE_OPENING_BALANCE', 'NEW_CODE', 'OTHER_NEW'],
        }),
        context(),
        enIntl,
      );
      expect(view.warnings).toEqual([
        'Before the opening balance: not counted in the balance',
        'Check this transaction',
      ]);
    });

    it('shows the counterparty only when it differs from the title', () => {
      expect(
        presentTransaction(
          row('c', '2026-11-09', { merchantDescription: null, counterpartyName: 'Anna' }),
          context(),
          enIntl,
        ).counterparty,
      ).toBeNull();
      expect(
        presentTransaction(row('c', '2026-11-09', { counterpartyName: 'Anna' }), context(), enIntl)
          .counterparty,
      ).toBe('Anna');
    });

    it('disables category changes on an archived account', () => {
      expect(
        presentTransaction(
          row('a', '2026-11-09', { categoryId: 'food' }),
          context({ archived: true }),
          enIntl,
        ).canCategorize,
      ).toBe(false);
    });
  });
});
