import { useIntl } from 'react-intl';
import type { ReactNode } from 'react';
import { useWindowDimensions } from 'react-native';

import { Amount } from '@/components/shared';
import { Fonts } from '@/constants/theme';
import { layoutFor } from '@/features/shell/navigation';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { presentTransaction, type RowContext, type Transaction } from './model';
import { TransactionResults as PhoneResults } from './transaction-results.native';

export type TransactionResultsProps = {
  transactions: Transaction[];
  context: RowContext;
  byDate: boolean;
  onOpen?: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  selectedTransactionId?: string | null;
  listHeader?: ReactNode;
  listFooter?: ReactNode;
  /** Native only: web pages with the "Load more" button (spec S3 "Paging"). */
  onEndReached?: () => void;
};

/**
 * ADR 0007 Option B: a real semantic table on web (W06), not a div-based imitation. At phone
 * width the web build shows the M25 list, like the native app, so four columns never squeeze.
 */
export function TransactionResults(props: TransactionResultsProps) {
  const { width } = useWindowDimensions();
  return layoutFor(width) === 'phone' ? (
    <PhoneResults {...props} />
  ) : (
    <TransactionTable {...props} />
  );
}

function TransactionTable({
  transactions,
  context,
  onOpen,
  onCategory,
  selectedTransactionId,
}: TransactionResultsProps) {
  const intl = useIntl();
  const theme = useTheme();
  const cellStyle = {
    padding: '12px 8px',
    verticalAlign: 'top',
    overflowWrap: 'anywhere' as const,
    borderBottom: `1px solid ${theme.borderDefault}`,
  };
  const headerStyle = {
    padding: '8px',
    color: theme.textSecondary,
    fontSize: 12,
    fontWeight: 600,
    borderBottom: `1px solid ${theme.borderDefault}`,
  };
  // W06: the web category cell shows "{parent} › {name}".
  const webContext = { ...context, includeParent: true };
  return (
    <div style={{ width: '100%', overflowX: 'auto' }}>
      <table
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          tableLayout: 'auto',
          // Plain DOM elements do not inherit react-native-web's text styles: theme them here.
          fontFamily: Fonts?.sans,
          fontSize: 14,
          lineHeight: '20px',
          color: theme.textPrimary,
        }}
      >
        <thead>
          <tr>
            <th scope="col" style={{ ...headerStyle, textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.date' })}
            </th>
            <th scope="col" style={{ ...headerStyle, textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.description' })}
            </th>
            <th scope="col" style={{ ...headerStyle, textAlign: 'left' }}>
              {intl.formatMessage({ id: 'account.category' })}
            </th>
            <th scope="col" style={{ ...headerStyle, textAlign: 'right' }}>
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
                      cursor: onOpen ? 'pointer' : 'default',
                      minHeight: 44,
                      font: 'inherit',
                      padding: 0,
                    }}
                    aria-current={selectedTransactionId === transaction.id ? 'true' : undefined}
                    disabled={!onOpen}
                    onClick={onOpen ? () => onOpen(transaction.id) : undefined}
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
                          padding: '4px 10px',
                          font: 'inherit',
                          cursor: 'pointer',
                          color: view.uncategorized ? theme.statusWarning : theme.brandLink,
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
