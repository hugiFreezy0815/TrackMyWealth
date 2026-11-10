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
  return (
    !archived &&
    !transaction.internalTransfer &&
    !transaction.voidedAt &&
    !transaction.deletedAt &&
    !transaction.reconciliationAdjustment &&
    transaction.categoryId !== null
  );
}

export function canRemove(transaction: Transaction, archived: boolean): boolean {
  return (
    !archived &&
    transaction.removal !== null &&
    !transaction.voidedAt &&
    !transaction.deletedAt &&
    transaction.reconciliationAdjustment === null
  );
}

export function categoryLabel(
  category: Category,
  parent: Category | undefined,
  german: boolean,
): string {
  const label = german ? category.nameDe : category.nameEn;
  return parent ? `${german ? parent.nameDe : parent.nameEn} › ${label}` : label;
}

export function historyCategory(
  id: string | null,
  categories: Category[],
  german: boolean,
): string {
  const category = categories.find((item) => item.id === id);
  if (!category) return '–';
  return categoryLabel(
    category,
    categories.find((item) => item.id === category.parentId),
    german,
  );
}

export function historyDescription(
  entry: HistoryEntry,
  categories: Category[],
  german: boolean,
): {
  key: string;
  values: Record<string, string>;
} {
  const actor = entry.actorDisplayName?.trim() ?? '';
  const from = historyCategory(entry.fromCategoryId, categories, german);
  const to = historyCategory(entry.toCategoryId, categories, german);
  const values = {
    actor,
    from,
    to,
    reason: entry.reason ?? '',
    format: entry.importBatch?.fileFormat ?? '',
    date: entry.importBatch?.uploadedAt.slice(0, 10) ?? '',
  };
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
  const kind = kinds[entry.kind] ?? 'txn.history.other';
  const needsActor = ['CREATED', 'CATEGORY_CHANGED', 'CATEGORY_RESET', 'CORRECTED', 'VOIDED', 'DELETED', 'RESTORED'].includes(entry.kind) || !kinds[entry.kind];
  let key = needsActor && !actor ? kind + '.system' : kind;
  if (entry.kind === 'VOIDED' && !entry.reason) key += '.noReason';
  if (entry.kind === 'IMPORTED' && !entry.importBatch) key += '.unknown';
  return { key, values };
}
