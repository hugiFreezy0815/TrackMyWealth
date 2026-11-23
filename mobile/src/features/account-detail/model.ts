import type { IntlShape } from 'react-intl';

import { labelFor } from '@/api/enum-label';
import type { components } from '@/api/schema';
import { formatDate } from '@/format/date';
import { isPlainDecimal } from '@/format/decimal-parts';

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

/** Preserve the server order, including amount sorts that have no month headings. */
export function groupedTransactions(
  transactions: Transaction[],
  byDate: boolean,
): { month: string | null; rows: Transaction[] }[] {
  if (!byDate) return [{ month: null, rows: transactions }];
  const groups: { month: string | null; rows: Transaction[] }[] = [];
  for (const transaction of transactions) {
    const month = transaction.bookingDate.slice(0, 7);
    const last = groups[groups.length - 1];
    if (last?.month === month) last.rows.push(transaction);
    else groups.push({ month, rows: [transaction] });
  }
  return groups;
}

/** Use the exact ledger sign, even when the formatted amount rounds to zero. */
export function transactionDirection(amount: Transaction['amount']) {
  if (!isPlainDecimal(amount) || !/[1-9]/.test(amount)) return null;
  return amount.startsWith('-')
    ? { icon: '↗', messageId: 'account.outgoing' }
    : { icon: '↙', messageId: 'account.incoming' };
}

export function transactionPurpose(transaction: Transaction): string | null {
  const purpose = transaction.merchantDescription;
  return typeof purpose === 'string' && purpose.trim() ? purpose : null;
}

export function transactionHeading(transaction: Transaction, intl: Intl): string {
  if (transaction.reconciliationAdjustment) {
    return intl.formatMessage({ id: 'transactionType.VALUATION_ADJUSTMENT' });
  }
  return (
    transaction.counterpartyName?.trim() ||
    transactionPurpose(transaction)?.trim() ||
    transactionTypeLabel(transaction.transactionType, intl)
  );
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

/** The reversed original's booking date, when it is known; otherwise the generic label shows. */
export function reversalOf(
  transaction: Transaction,
  dates: RowContext['reversalDates'],
): string | null {
  if (!transaction.replacesTransactionId) return null;
  return dates.get(transaction.replacesTransactionId) ?? null;
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
  /** Booking dates by transaction ID: loaded rows plus originals read for reversals on page. */
  reversalDates: ReadonlyMap<string, string>;
  accountCurrency: string;
  archived: boolean;
  /** Web shows "{parent} › {name}". */
  includeParent: boolean;
};

export type RowPresentation = {
  title: string;
  typeLabel: string;
  purpose: string | null;
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
  const counterparty = row.counterpartyName?.trim() || null;
  const purpose = transactionPurpose(row);
  const title = transactionHeading(row, intl);
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
  const reversalDate = reversalOf(row, context.reversalDates);
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
    purpose:
      !row.reconciliationAdjustment && counterparty && purpose?.trim() !== title ? purpose : null,
    categoryLabel,
    uncategorized,
    canCategorize:
      !context.archived &&
      !row.voidedAt &&
      !row.deletedAt &&
      !row.internalTransfer &&
      !row.reconciliationAdjustment &&
      row.categoryId !== null,
    voided: !!row.voidedAt,
    lifecycle,
    warnings: transactionWarningTexts(row.warnings, intl),
    converted: convertedAmount(row, context.accountCurrency),
  };
}
