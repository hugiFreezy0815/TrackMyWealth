import { useIntl } from 'react-intl';

import { Amount } from '@/components/shared';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { presentTransaction, type RowContext, type Transaction } from './model';

export type TransactionResultsProps = {
  transactions: Transaction[];
  context: RowContext;
  byDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  /** Native only: web pages with the "Load more" button (spec S3 "Paging"). */
  onEndReached?: () => void;
};

const cellStyle = {
  padding: '12px 6px',
  verticalAlign: 'top',
  overflowWrap: 'anywhere' as const,
};

/** ADR 0007 Option B: a real semantic table on web, not a div-based imitation. */
export function TransactionResults({
  transactions,
  context,
  onOpen,
  onCategory,
}: TransactionResultsProps) {
  const intl = useIntl();
  const theme = useTheme();
  // W06: the web category cell shows "{parent} › {name}".
  const webContext = { ...context, includeParent: true };
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
            const view = presentTransaction(transaction, webContext, intl);
            return (
              <tr key={transaction.id}>
                <td style={cellStyle}>{formatDate(transaction.bookingDate, intl.locale)}</td>
                <td style={cellStyle}>
                  <button
                    type="button"
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
                    {view.title}
                  </button>
                  <div style={{ color: theme.textSecondary }}>
                    {[view.typeLabel, view.counterparty].filter(Boolean).join(' · ')}
                  </div>
                  {view.voided ? (
                    <div style={{ color: theme.statusWarning }}>
                      {intl.formatMessage({ id: 'account.voided' })}
                      <span style={visuallyHidden}>
                        {` (${intl.formatMessage({ id: 'account.voidedA11y' })})`}
                      </span>
                    </div>
                  ) : null}
                  {view.lifecycle.map((note) => (
                    <div key={note} style={{ color: theme.textSecondary }}>
                      {note}
                    </div>
                  ))}
                  {view.warnings.map((warning) => (
                    <div key={warning} style={{ color: theme.statusWarning }}>
                      <span aria-hidden="true">⚠ </span>
                      {warning}
                    </div>
                  ))}
                </td>
                <td style={cellStyle}>
                  {view.categoryLabel ? (
                    view.canCategorize ? (
                      <button
                        type="button"
                        style={{
                          minHeight: 44,
                          color: view.uncategorized ? theme.statusWarning : 'inherit',
                          background: 'transparent',
                          border: '1px solid currentColor',
                          borderRadius: 8,
                        }}
                        onClick={() => onCategory(transaction)}
                        aria-label={intl.formatMessage(
                          { id: 'account.changeCategoryOf' },
                          { category: view.categoryLabel },
                        )}
                      >
                        {view.uncategorized ? '⚠ ' : ''}
                        {view.categoryLabel} ⌄
                      </button>
                    ) : (
                      <span
                        style={{
                          color: view.uncategorized ? theme.statusWarning : theme.brandLink,
                        }}
                      >
                        {view.uncategorized ? '⚠ ' : ''}
                        {view.categoryLabel}
                      </span>
                    )
                  ) : null}
                </td>
                <td style={{ ...cellStyle, textAlign: 'right' }}>
                  <span
                    style={{
                      textDecoration: view.voided ? 'line-through' : undefined,
                      whiteSpace: 'normal',
                    }}
                  >
                    <Amount
                      value={transaction.amount}
                      currency={transaction.currency}
                      signRole="change"
                    />
                  </span>
                  {view.converted ? (
                    <div style={{ color: theme.textSecondary }}>
                      <Amount
                        value={view.converted.value}
                        currency={view.converted.currency}
                        approx
                      />
                      {view.converted.estimated
                        ? ` · ${intl.formatMessage({ id: 'account.estimated' })}`
                        : ''}
                    </div>
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

const visuallyHidden = {
  position: 'absolute' as const,
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap' as const,
};
