import type { IntlShape } from 'react-intl';

import { labelFor } from '@/api/enum-label';
import type { components } from '@/api/schema';
import { formatDate, formatRelativeDay } from '@/format/date';

export type Transaction = components['schemas']['TransactionResponse'];
export type Category = components['schemas']['CategoryResponse'];
export type AccountSummary = components['schemas']['AccountSummaryResponse'];
export type AccountListItem = components['schemas']['AccountListItemResponse'];
export type Reconciliation = components['schemas']['ReconciliationStatusResponse'];

type Intl = Pick<IntlShape, 'locale' | 'formatMessage'>;

/** Message ids of the ledger's transaction types (V10 check constraint). */
export const TRANSACTION_TYPE_LABELS = {
  INCOME: 'transactionType.INCOME',
  EXPENSE: 'transactionType.EXPENSE',
  TRANSFER: 'transactionType.TRANSFER',
  DEPOSIT: 'transactionType.DEPOSIT',
  WITHDRAWAL: 'transactionType.WITHDRAWAL',
  BUY: 'transactionType.BUY',
  SELL: 'transactionType.SELL',
  DIVIDEND: 'transactionType.DIVIDEND',
  INTEREST: 'transactionType.INTEREST',
  FEE: 'transactionType.FEE',
  TAX: 'transactionType.TAX',
  REFUND: 'transactionType.REFUND',
  DEBT_REPAYMENT: 'transactionType.DEBT_REPAYMENT',
  PENSION_CONTRIBUTION: 'transactionType.PENSION_CONTRIBUTION',
  CREDIT_CARD_PURCHASE: 'transactionType.CREDIT_CARD_PURCHASE',
  SETTLEMENT: 'transactionType.SETTLEMENT',
  VALUATION_ADJUSTMENT: 'transactionType.VALUATION_ADJUSTMENT',
  CORPORATE_ACTION: 'transactionType.CORPORATE_ACTION',
} as const;

/** Message ids of the account types (V4 check constraint). */
export const ACCOUNT_TYPE_LABELS = {
  CASH: 'accountType.CASH',
  SAVINGS: 'accountType.SAVINGS',
  SECURITIES: 'accountType.SECURITIES',
  MANAGED_MANDATE: 'accountType.MANAGED_MANDATE',
  PENSION: 'accountType.PENSION',
  VESTED_BENEFITS: 'accountType.VESTED_BENEFITS',
  CREDIT_CARD: 'accountType.CREDIT_CARD',
  MORTGAGE: 'accountType.MORTGAGE',
  LOAN: 'accountType.LOAN',
  CRYPTO: 'accountType.CRYPTO',
  CUSTOM_ASSET: 'accountType.CUSTOM_ASSET',
} as const;

/** Row warnings with their own text; every other code reads "Check this transaction". */
export const TRANSACTION_WARNING_LABELS = {
  BOOKED_BEFORE_OPENING_BALANCE: 'account.txnWarning.BOOKED_BEFORE_OPENING_BALANCE',
} as const;
export const OTHER_TRANSACTION_WARNING = 'account.warning';

export const transactionTypeLabel = (code: string, intl: Intl): string =>
  intl.formatMessage({
    id: labelFor(TRANSACTION_TYPE_LABELS, code, 'transactionType.other'),
  });

export const accountTypeLabel = (code: string, intl: Intl): string =>
  intl.formatMessage({ id: labelFor(ACCOUNT_TYPE_LABELS, code, 'accountType.other') });

/** Authoritative account value provenance from the server, not inferred from capabilities. */
const VALUE_BASIS_LABELS = {
  LEDGER: 'valueBasis.LEDGER',
  LEDGER_EMPTY: 'valueBasis.LEDGER_EMPTY',
  LEDGER_FROM_OPENING_BALANCE: 'valueBasis.LEDGER_FROM_OPENING_BALANCE',
  LEDGER_FROM_STATEMENT_BALANCE: 'valueBasis.LEDGER_FROM_STATEMENT_BALANCE',
  LATEST_SNAPSHOT: 'valueBasis.LATEST_SNAPSHOT',
  MANUAL_VALUATION: 'valueBasis.MANUAL_VALUATION',
  ORIGINAL_PRINCIPAL: 'valueBasis.ORIGINAL_PRINCIPAL',
  ORIGINAL_PRINCIPAL_LESS_REPAYMENTS: 'valueBasis.ORIGINAL_PRINCIPAL_LESS_REPAYMENTS',
} as const;

/** Unknown/new server values never imply that a particular provenance is verified. */
export function valueBasisExplanation(basis: string | null | undefined, intl: Intl): string {
  if (!basis) return intl.formatMessage({ id: 'account.valueSourceUnavailable' });
  const messageId = labelFor(VALUE_BASIS_LABELS, basis, 'account.valueSourceUnavailable');
  if (messageId === 'account.valueSourceUnavailable') {
    return intl.formatMessage({ id: messageId });
  }
  return intl.formatMessage(
    { id: 'account.valueFrom' },
    { basis: intl.formatMessage({ id: messageId }) },
  );
}

/** Distinct row warning texts: unknown codes collapse into one generic line. */
export const transactionWarningTexts = (codes: readonly string[], intl: Intl): string[] =>
  Array.from(
    new Set(
      codes.map((code) =>
        intl.formatMessage({
          id: labelFor(TRANSACTION_WARNING_LABELS, code, OTHER_TRANSACTION_WARNING),
        }),
      ),
    ),
  );

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

/** A category id the cached list does not know: the list is reloaded once, then "Category". */
export const hasUnknownCategory = (transactions: Transaction[], categories: Category[]) =>
  transactions.some(
    (transaction) =>
      transaction.categoryId !== null &&
      !transaction.internalTransfer &&
      categoryFor(transaction, categories) === undefined,
  );

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

/** "Today · 20 Oct 2026", "Yesterday · 19 Oct 2026", else just the date. */
export function groupHeading(date: string, intl: Intl, today = new Date()): string {
  const formatted = formatDate(date, intl.locale);
  const relative = formatRelativeDay(date, intl, today);
  return relative === formatted
    ? formatted
    : intl.formatMessage({ id: 'account.groupRelative' }, { day: relative, date: formatted });
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

export type ReconciliationLine = {
  /** ICU message with `{date}` and, where it applies, `{amount}`. */
  messageId: string;
  tone: 'success' | 'warning' | 'neutral';
  /** A visible symbol next to the text, so the state never relies on colour alone. */
  icon: '✓' | '⚠' | null;
  date: string | null;
  difference: { value: string; currency: string } | null;
};

const RECONCILIATION_LINES = {
  RECONCILED: { messageId: 'account.reconciled', tone: 'success', icon: '✓' },
  OPEN_DIFFERENCE: { messageId: 'account.difference', tone: 'warning', icon: '⚠' },
  DISMISSED_DIFFERENCE: { messageId: 'account.dismissedDifference', tone: 'neutral', icon: null },
  NEVER: { messageId: 'account.neverReconciled', tone: 'neutral', icon: null },
  NO_OPENING_BALANCE: { messageId: 'account.noOpening', tone: 'neutral', icon: null },
} as const;

/** FR-REC: the header line; hidden (null) for not reconcilable, cash scope or unknown states. */
export function reconciliationLine(reconciliation: Reconciliation): ReconciliationLine | null {
  const line = Object.prototype.hasOwnProperty.call(RECONCILIATION_LINES, reconciliation.status)
    ? RECONCILIATION_LINES[reconciliation.status as keyof typeof RECONCILIATION_LINES]
    : null;
  if (!line) return null;
  const needsAmount =
    reconciliation.status === 'OPEN_DIFFERENCE' || reconciliation.status === 'DISMISSED_DIFFERENCE';
  return {
    ...line,
    date: reconciliation.asOf,
    difference:
      needsAmount && reconciliation.openDifference !== null && reconciliation.currency !== null
        ? { value: reconciliation.openDifference, currency: reconciliation.currency }
        : null,
  };
}

/** The original's booking date when it is among the loaded rows; otherwise none is shown. */
export function reversalOf(transaction: Transaction, loaded: Transaction[]): string | null {
  if (!transaction.replacesTransactionId) return null;
  return loaded.find((row) => row.id === transaction.replacesTransactionId)?.bookingDate ?? null;
}

/** "≈ {amount}" in the account currency, only for a booking in another currency (#479). */
export function convertedAmount(
  transaction: Transaction,
  accountCurrency: string,
): { value: string; currency: string; estimated: boolean } | null {
  if (transaction.currency === accountCurrency || transaction.amountInAccountCurrency === null) {
    return null;
  }
  return {
    value: transaction.amountInAccountCurrency,
    currency: accountCurrency,
    estimated: transaction.fxRateEstimated,
  };
}

export type RowContext = {
  categories: Category[];
  /** Every loaded row, to find the original of a reversal. */
  loaded: Transaction[];
  accountCurrency: string;
  archived: boolean;
  /** Web shows "{parent} › {name}". */
  includeParent: boolean;
};

export type RowPresentation = {
  title: string;
  typeLabel: string;
  /** The counterparty when it differs from the title. */
  counterparty: string | null;
  categoryLabel: string | null;
  uncategorized: boolean;
  canCategorize: boolean;
  voided: boolean;
  /** "Reversal of {date}", "Corrected": the lifecycle notes after the title. */
  lifecycle: string[];
  warnings: string[];
  converted: ReturnType<typeof convertedAmount>;
};

/** Everything a row shows besides its amount: one source for the native list and the web table. */
export function presentTransaction(
  row: Transaction,
  context: RowContext,
  intl: Intl,
): RowPresentation {
  const german = intl.locale.startsWith('de');
  const category = categoryFor(row, context.categories);
  const typeLabel = transactionTypeLabel(row.transactionType, intl);
  const title = row.reconciliationAdjustment
    ? intl.formatMessage({ id: 'transactionType.VALUATION_ADJUSTMENT' })
    : row.merchantDescription?.trim() || row.counterpartyName?.trim() || typeLabel;
  const counterparty = row.counterpartyName?.trim() || null;
  const uncategorized = isUncategorized(row, context.categories);
  const categoryLabel = row.internalTransfer
    ? intl.formatMessage({ id: 'account.internalTransfer' })
    : uncategorized
      ? // Spec S3 row 4: the app's own word in warning style, not the category's server name.
        intl.formatMessage({ id: 'account.uncategorizedLabel' })
      : category
        ? categoryName(category, context.categories, german, context.includeParent)
        : row.categoryId !== null
          ? intl.formatMessage({ id: 'account.categoryFallback' })
          : null;
  const reversalDate = reversalOf(row, context.loaded);
  const lifecycle = [
    row.replacesTransactionId
      ? reversalDate
        ? intl.formatMessage(
            { id: 'account.reversalOf' },
            { date: formatDate(reversalDate, intl.locale) },
          )
        : intl.formatMessage({ id: 'account.reversal' })
      : null,
    row.correctsTransactionId ? intl.formatMessage({ id: 'account.corrected' }) : null,
  ].filter((note): note is string => note !== null);
  return {
    title,
    typeLabel,
    counterparty: counterparty !== null && counterparty !== title ? counterparty : null,
    categoryLabel,
    uncategorized,
    canCategorize:
      !context.archived &&
      !row.voidedAt &&
      !row.internalTransfer &&
      !row.reconciliationAdjustment &&
      row.categoryId !== null,
    voided: !!row.voidedAt,
    lifecycle,
    warnings: transactionWarningTexts(row.warnings, intl),
    converted: convertedAmount(row, context.accountCurrency),
  };
}
