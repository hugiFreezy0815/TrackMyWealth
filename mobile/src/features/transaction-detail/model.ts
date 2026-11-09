import type { components } from '@/api/schema';

export type Transaction = components['schemas']['TransactionResponse'];
export type Category = components['schemas']['CategoryResponse'];
export type HistoryEntry = components['schemas']['TransactionHistoryEntryResponse'];

export function transactionTitle(transaction: Transaction): string {
  return transaction.reconciliationAdjustment
    ? 'VALUATION_ADJUSTMENT'
    : transaction.merchantDescription?.trim() ||
      transaction.counterpartyName?.trim() ||
      transaction.transactionType;
}

export function canChangeCategory(transaction: Transaction, archived: boolean): boolean {
  return !archived && !transaction.internalTransfer && !transaction.voidedAt &&
    !transaction.deletedAt && !transaction.reconciliationAdjustment &&
    transaction.categoryId !== null;
}

export function canRemove(transaction: Transaction, archived: boolean): boolean {
  return !archived && transaction.removal !== null && !transaction.voidedAt &&
    !transaction.deletedAt && transaction.reconciliationAdjustment === null;
}

export function categoryLabel(category: Category, parent: Category | undefined, german: boolean): string {
  const label = german ? category.nameDe : category.nameEn;
  return parent ? `${german ? parent.nameDe : parent.nameEn} › ${label}` : label;
}

export function historyCategory(id: string | null, categories: Category[], german: boolean): string {
  const category = categories.find((item) => item.id === id);
  if (!category) return '–';
  return categoryLabel(category, categories.find((item) => item.id === category.parentId), german);
}

export function historyDescription(entry: HistoryEntry, categories: Category[], german: boolean): {
  key: string; values: Record<string, string>;
} {
  const actor = entry.actorDisplayName ?? '';
  const from = historyCategory(entry.fromCategoryId, categories, german);
  const to = historyCategory(entry.toCategoryId, categories, german);
  const values = { actor, from, to, reason: entry.reason ?? '' };
  const kinds: Record<string, string> = {
    IMPORTED: 'txn.history.imported',
    CREATED: 'txn.history.created',
    CATEGORY_ASSIGNED: 'txn.history.assigned',
    CATEGORY_CHANGED: 'txn.history.changed',
    CATEGORY_RESET: 'txn.history.reset',
    CORRECTED: 'txn.history.corrected',
    VOIDED: 'txn.history.voided',
    DELETED: 'txn.history.deleted',
    RESTORED: 'txn.history.restored',
  };
  return { key: kinds[entry.kind] ?? 'txn.history.other', values };
}
