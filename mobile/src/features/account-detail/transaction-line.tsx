import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { Amount, amountTexts } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { presentTransaction, type RowContext, type Transaction } from './model';

export type TransactionLineProps = {
  transaction: Transaction;
  context: RowContext;
  /** Sorted by amount: no date headers, so the row shows its own date. */
  showDate: boolean;
  onOpen: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
};

export function TransactionLine({
  transaction: row,
  context,
  showDate,
  onOpen,
  onCategory,
}: TransactionLineProps) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const view = presentTransaction(row, context, intl);
  const date = formatDate(row.bookingDate, intl.locale);
  const amount = amountTexts(intl, {
    value: row.amount,
    currency: row.currency,
    signRole: 'change',
    masked: hidden,
  });
  const converted = view.converted
    ? amountTexts(intl, {
        value: view.converted.value,
        currency: view.converted.currency,
        approx: true,
        masked: hidden,
      })
    : null;
  const muted = { color: theme.textSecondary, ...TypeScale.caption };
  // The row's accessible name replaces its children's text, so it carries every visible fact.
  const label = [
    view.title,
    date,
    view.typeLabel,
    view.counterparty,
    amount.label,
    converted?.label,
    view.converted?.estimated ? intl.formatMessage({ id: 'account.estimatedFx' }) : null,
    view.voided ? intl.formatMessage({ id: 'account.voidedA11y' }) : null,
    ...view.lifecycle,
    view.categoryLabel,
    ...view.warnings,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <View
      style={{
        paddingVertical: Spacing.compact,
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
        gap: Spacing.one,
      }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={label}
        onPress={() => onOpen(row.id)}
        style={{
          minHeight: 44,
          flexDirection: 'row',
          justifyContent: 'space-between',
          gap: Spacing.two,
          alignItems: 'center',
        }}
      >
        <View style={{ flex: 1, gap: Spacing.one }}>
          <Text
            numberOfLines={1}
            style={{ ...TypeScale.bodyMedium, color: theme.textPrimary, fontWeight: '600' }}
          >
            {view.title}
          </Text>
          <Text style={muted}>
            {[view.typeLabel, view.counterparty, showDate ? date : null]
              .filter(Boolean)
              .join(' · ')}
          </Text>
          {view.voided ? (
            <Text style={{ ...muted, color: theme.statusWarning }}>
              {intl.formatMessage({ id: 'account.voided' })}
            </Text>
          ) : null}
          {view.lifecycle.map((note) => (
            <Text key={note} style={muted}>
              {note}
            </Text>
          ))}
          {view.warnings.map((warning) => (
            <Text key={warning} style={{ ...muted, color: theme.statusWarning }}>
              ⚠ {warning}
            </Text>
          ))}
        </View>
        <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
          <Amount
            value={row.amount}
            currency={row.currency}
            signRole="change"
            style={{
              ...TypeScale.numberMedium,
              color: theme.textPrimary,
              textDecorationLine: view.voided ? 'line-through' : 'none',
            }}
          />
          {view.converted ? (
            <Text style={muted}>
              <Amount
                value={view.converted.value}
                currency={view.converted.currency}
                approx
                style={muted}
              />
              {view.converted.estimated
                ? ` · ${intl.formatMessage({ id: 'account.estimated' })}`
                : ''}
            </Text>
          ) : null}
        </View>
      </Pressable>
      {view.categoryLabel ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={intl.formatMessage(
            { id: 'account.changeCategoryOf' },
            { category: view.categoryLabel },
          )}
          aria-disabled={!view.canCategorize}
          disabled={!view.canCategorize}
          onPress={() => onCategory(row)}
          style={{
            minHeight: 44,
            alignSelf: 'flex-start',
            justifyContent: 'center',
            opacity: view.canCategorize ? 1 : 0.75,
          }}
        >
          <Text
            style={{
              ...TypeScale.labelMedium,
              // Internal transfers use the teal brand link colour (spec S3 row 4).
              color: view.uncategorized ? theme.statusWarning : theme.brandLink,
            }}
          >
            {view.uncategorized ? '⚠ ' : ''}
            {view.categoryLabel}
            {view.canCategorize ? ' ⌄' : ''}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}
