import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';
import { Amount } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { categoryFor, categoryName, isUncategorized, type Category, type Transaction } from './model';

export type TransactionLineProps = {
  transaction: Transaction;
  categories: Category[];
  archived: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
};

export function transactionTitle(row: Transaction): string {
  if (row.reconciliationAdjustment) return 'VALUATION_ADJUSTMENT';
  return row.merchantDescription?.trim() || row.counterpartyName?.trim() || row.transactionType;
}

export function TransactionLine({
  transaction: row,
  categories,
  archived,
  onOpen,
  onCategory,
}: TransactionLineProps) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const german = intl.locale.startsWith('de');
  const category = categoryFor(row, categories);
  const uncategorized = isUncategorized(row, categories);
  const categoryLabel = row.internalTransfer
    ? intl.formatMessage({ id: 'account.internalTransfer' })
    : category ? categoryName(category, categories, german) : null;
  const canCategorize = !archived && !row.voidedAt && !row.internalTransfer &&
    !row.reconciliationAdjustment && category !== undefined;
  const title = transactionTitle(row);
  const voided = !!row.voidedAt;
  const muted = { color: theme.textSecondary, ...TypeScale.caption };
  return (
    <View style={{
      paddingVertical: Spacing.compact, borderBottomWidth: 1, borderBottomColor: theme.borderDefault,
      gap: Spacing.one,
    }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={[
          title, row.bookingDate, row.transactionType,
          voided ? intl.formatMessage({ id: 'account.voided' }) : '',
          categoryLabel ?? '',
          hidden ? intl.formatMessage({ id: 'value.hidden' }) : '',
        ].filter(Boolean).join(', ')}
        onPress={() => onOpen(row.id)}
        style={{ minHeight: 44, flexDirection: 'row', justifyContent: 'space-between', gap: Spacing.two, alignItems: 'center' }}
      >
        <View style={{ flex: 1, gap: Spacing.one }}>
          <Text numberOfLines={2} style={{ ...TypeScale.bodyMedium, color: theme.textPrimary, fontWeight: '600' }}>
            {title === 'VALUATION_ADJUSTMENT'
              ? intl.formatMessage({ id: 'account.statementAdjustment' })
              : title}
          </Text>
          <Text style={muted}>
            {row.transactionType} · {formatDate(row.bookingDate, intl.locale)}
          </Text>
          {voided ? <Text style={{ ...muted, color: theme.statusWarning }}>{intl.formatMessage({ id: 'account.voided' })}</Text> : null}
          {row.replacesTransactionId ? <Text style={muted}>{intl.formatMessage({ id: 'account.reversal' })}</Text> : null}
          {row.correctsTransactionId ? <Text style={muted}>{intl.formatMessage({ id: 'account.corrected' })}</Text> : null}
          {row.warnings.length ? <Text style={{ ...muted, color: theme.statusWarning }}>⚠ {intl.formatMessage({ id: 'account.warning' })}</Text> : null}
        </View>
        <Amount value={row.amount} currency={row.currency} signRole="change"
          style={{ ...TypeScale.numberMedium, color: theme.textPrimary, textDecorationLine: voided ? 'line-through' : 'none' }} />
      </Pressable>
      {categoryLabel ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={intl.formatMessage({ id: 'account.changeCategory' }) + ': ' + categoryLabel}
          aria-disabled={!canCategorize}
          disabled={!canCategorize}
          onPress={() => onCategory(row)}
          style={{ minHeight: 44, alignSelf: 'flex-start', justifyContent: 'center', opacity: canCategorize ? 1 : 0.75 }}
        >
          <Text style={{ ...TypeScale.labelMedium, color: uncategorized ? theme.statusWarning : theme.brandLink }}>
            {categoryLabel}{canCategorize ? ' ⌄' : ''}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}
