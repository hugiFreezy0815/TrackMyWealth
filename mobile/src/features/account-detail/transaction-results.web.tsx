import { useIntl } from 'react-intl';
import { Amount } from '@/components/shared';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { categoryFor, categoryName, type Category, type Transaction } from './model';

export type TransactionResultsProps = {
  transactions: Transaction[];
  categories: Category[];
  archived: boolean;
  byDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  selectedTransactionId?: string | null;
};

/** ADR 0007 Option B: a real semantic table on web, not a div-based imitation. */
export function TransactionResults({
  transactions,
  categories,
  archived,
  onOpen,
  onCategory,
  selectedTransactionId,
}: TransactionResultsProps) {
  const intl = useIntl();
  const german = intl.locale.startsWith('de');
  const hidden = useAmountsHidden();
  return (
    <div style={{ width: '100%', overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'auto' }}>
        <thead>
          <tr>
            <th scope="col" style={{ textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.date' })}
            </th>
            <th scope="col" style={{ textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.description' })}
            </th>
            <th scope="col" style={{ textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.category' })}
            </th>
            <th scope="col" style={{ textAlign: 'right' }}>
              {intl.formatMessage({ id: 'account.amount' })}
            </th>
          </tr>
        </thead>
        <tbody>
          {transactions.map((transaction) => {
            const category = categoryFor(transaction, categories);
            const categoryText = transaction.internalTransfer
              ? intl.formatMessage({ id: 'account.internalTransfer' })
              : category
                ? categoryName(category, categories, german, true)
                : null;
            const voided = !!transaction.voidedAt;
            const name = transaction.reconciliationAdjustment
              ? intl.formatMessage({ id: 'account.statementAdjustment' })
              : transaction.merchantDescription ||
                transaction.counterpartyName ||
                transaction.transactionType;
            const canCategorize =
              !archived &&
              !voided &&
              !transaction.internalTransfer &&
              !transaction.reconciliationAdjustment &&
              category != null;
            const cellStyle = {
              padding: '12px 6px',
              verticalAlign: 'top',
              overflowWrap: 'anywhere' as const,
            };
            return (
              <tr key={transaction.id}>
                <td style={cellStyle}>{formatDate(transaction.bookingDate, intl.locale)}</td>
                <td style={cellStyle}>
                  <button
                    type="button"
                    aria-current={selectedTransactionId === transaction.id ? "true" : undefined}
                    style={{
                      border: 0,
                      background: 'transparent',
                      color: 'inherit',
                      textAlign: 'left',
                      cursor: 'pointer',
                      minHeight: 44,
                      font: 'inherit',
                      padding: 0,
                    }}
                    onClick={() => onOpen(transaction.id)}
                  >
                    {name}
                    {voided ? ` · ${intl.formatMessage({ id: 'account.voided' })}` : ''}
                    {transaction.replacesTransactionId
                      ? ` · ${intl.formatMessage({ id: 'account.reversal' })}`
                      : ''}
                    {transaction.correctsTransactionId
                      ? ` · ${intl.formatMessage({ id: 'account.corrected' })}`
                      : ''}
                  </button>
                </td>
                <td style={cellStyle}>
                  {categoryText ? (
                    canCategorize ? (
                      <button
                        type="button"
                        style={{
                          minHeight: 44,
                          color: 'inherit',
                          background: 'transparent',
                          border: '1px solid currentColor',
                          borderRadius: 8,
                        }}
                        onClick={() => onCategory(transaction)}
                        aria-label={
                          intl.formatMessage({ id: 'account.changeCategory' }) + ': ' + categoryText
                        }
                      >
                        {categoryText} ⌄
                      </button>
                    ) : (
                      <span>{categoryText}</span>
                    )
                  ) : null}
                </td>
                <td style={{ ...cellStyle, textAlign: 'right' }}>
                  <span
                    style={{
                      textDecoration: voided ? 'line-through' : undefined,
                      whiteSpace: 'normal',
                    }}
                  >
                    <Amount
                      value={transaction.amount}
                      currency={transaction.currency}
                      signRole="change"
                    />
                  </span>
                  {hidden ? null : transaction.amountInAccountCurrency &&
                    transaction.fxRateEstimated ? (
                    <span title={intl.formatMessage({ id: 'account.estimatedFx' })}> ≈</span>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
