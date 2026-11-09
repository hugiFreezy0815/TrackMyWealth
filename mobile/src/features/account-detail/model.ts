import type { components } from '@/api/schema';

export type Transaction = components['schemas']['TransactionResponse'];
export type Category = components['schemas']['CategoryResponse'];
export type AccountSummary = components['schemas']['AccountSummaryResponse'];
export type AccountListItem = components['schemas']['AccountListItemResponse'];

export function uniqueTransactions(pages: { content: Transaction[] }[]): Transaction[] {
  const seen = new Set<string>();
  return pages.flatMap((page) =>
    page.content.filter((transaction) => {
      if (seen.has(transaction.id)) return false;
      seen.add(transaction.id);
      return true;
    }),
  );
}

export function categoryFor(
  transaction: Transaction,
  categories: Category[],
): Category | undefined {
  return categories.find((category) => category.id === transaction.categoryId);
}

export function isUncategorized(transaction: Transaction, categories: Category[]): boolean {
  return (
    !transaction.internalTransfer && categoryFor(transaction, categories)?.code === 'UNCATEGORIZED'
  );
}

/** The server provides the category tree; the screen only formats its names. */
export function categoryName(
  category: Category,
  categories: Category[],
  german: boolean,
  includeParent = false,
): string {
  const name = german ? category.nameDe : category.nameEn;
  const parent = includeParent
    ? categories.find((candidate) => candidate.id === category.parentId)
    : undefined;
  return parent ? `${german ? parent.nameDe : parent.nameEn} › ${name}` : name;
}

/** Sorting by amount is server-side; date headings would imply a chronology that is not there. */
export function groupedTransactions(
  transactions: Transaction[],
  byDate: boolean,
): { date: string | null; rows: Transaction[] }[] {
  if (!byDate) return [{ date: null, rows: transactions }];
  const groups: { date: string | null; rows: Transaction[] }[] = [];
  for (const transaction of transactions) {
    const last = groups[groups.length - 1];
    if (last && last.date === transaction.bookingDate) last.rows.push(transaction);
    else groups.push({ date: transaction.bookingDate, rows: [transaction] });
  }
  return groups;
}

export function accountValuation(
  account: AccountSummary | undefined,
  active: AccountListItem[] | undefined,
  archived: AccountListItem[] | undefined,
): AccountListItem | undefined {
  if (!account) return undefined;
  return (account.status === 'ARCHIVED' ? archived : active)?.find(
    (candidate) => candidate.id === account.id,
  );
}
