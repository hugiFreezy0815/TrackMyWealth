// S4 acceptance: keep lifecycle and category rules independent of presentation.
import type { Category, Transaction, HistoryEntry } from './model';
import {
  canChangeCategory,
  canRemove,
  categoryLabel,
  historyDescription,
  transactionTitle,
} from './model';

const transaction = (changes: Partial<Transaction> = {}): Transaction =>
  ({
    categoryId: 'groceries',
    internalTransfer: false,
    voidedAt: null,
    deletedAt: null,
    reconciliationAdjustment: null,
    removal: 'VOID',
    merchantDescription: 'Migros',
    counterpartyName: 'Other',
    transactionType: 'CARD_PAYMENT',
    ...changes,
  }) as Transaction;

describe('S4 transaction detail rules', () => {
  it('does not offer a category for dividend or internal transfer', () => {
    expect(canChangeCategory(transaction({ categoryId: null }), false)).toBe(false);
    expect(canChangeCategory(transaction({ internalTransfer: true }), false)).toBe(false);
    expect(canChangeCategory(transaction({ reconciliationAdjustment: 'ADJUSTMENT' }), false)).toBe(
      false,
    );
    expect(canChangeCategory(transaction(), true)).toBe(false);
    expect(canChangeCategory(transaction(), false)).toBe(true);
  });

  it('only offers delete for a mutable, removable transaction', () => {
    expect(canRemove(transaction(), false)).toBe(true);
    expect(canRemove(transaction({ removal: null }), false)).toBe(false);
    expect(canRemove(transaction({ voidedAt: '2026-11-09T09:00:00Z' }), false)).toBe(false);
    expect(canRemove(transaction({ reconciliationAdjustment: 'ADJUSTMENT' }), false)).toBe(false);
    expect(canRemove(transaction(), true)).toBe(false);
  });

  it('uses an adjustment title and never mistakes it for a merchant', () => {
    expect(transactionTitle(transaction())).toBe('Migros');
    expect(transactionTitle(transaction({ reconciliationAdjustment: 'ADJUSTMENT' }))).toBe(
      'VALUATION_ADJUSTMENT',
    );
  });

  it('formats the two-level category tree in either language', () => {
    const parent = { nameEn: 'Food', nameDe: 'Essen' } as Category;
    const item = { nameEn: 'Groceries', nameDe: 'Lebensmittel' } as Category;
    expect(categoryLabel(item, parent, false)).toBe('Food › Groceries');
    expect(categoryLabel(item, parent, true)).toBe('Essen › Lebensmittel');
  });

  it('treats history kinds as an open set and resolves category IDs', () => {
    const categories = [
      { id: 'g', nameEn: 'Groceries', nameDe: 'Lebensmittel', parentId: null },
      { id: 'd', nameEn: 'Dining out', nameDe: 'Restaurant', parentId: null },
    ] as Category[];
    const entry = {
      kind: 'CATEGORY_CHANGED',
      fromCategoryId: 'g',
      toCategoryId: 'd',
      actorDisplayName: 'Anna',
      reason: null,
    } as HistoryEntry;
    expect(historyDescription(entry, categories, false)).toEqual({
      key: 'txn.history.changed',
      values: {
        actor: 'Anna',
        from: 'Groceries',
        to: 'Dining out',
        reason: '',
        format: '',
        date: '',
      },
    });
    expect(historyDescription({ ...entry, kind: 'FUTURE_KIND' }, categories, true).key).toBe(
      'txn.history.other',
    );
    expect(
      historyDescription({ ...entry, kind: 'CORRECTED', actorDisplayName: null }, categories, true)
        .key,
    ).toBe('txn.history.corrected.system');
    expect(
      historyDescription({ ...entry, kind: 'RESTORED', actorDisplayName: null }, categories, true)
        .key,
    ).toBe('txn.history.restored.system');
    expect(
      historyDescription(
        { ...entry, kind: 'IMPORTED', importBatch: null, actorDisplayName: null },
        categories,
        true,
      ).key,
    ).toBe('txn.history.imported.unknown');
    expect(
      historyDescription({ ...entry, kind: 'UNKNOWN', actorDisplayName: null }, categories, true)
        .key,
    ).toBe('txn.history.other.system');
    const inactive = categories.map((category) => ({ ...category, active: false }));
    expect(historyDescription(entry, inactive, true).values.from).toBe('Lebensmittel');
  });
});
