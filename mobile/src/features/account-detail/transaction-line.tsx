import { useIntl } from 'react-intl';
import { Platform, Pressable, Text, View } from 'react-native';

import { Amount, amountTexts } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatBookingDay, formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import {
  presentTransaction,
  transactionDirection,
  type RowContext,
  type Transaction,
} from './model';
import { transactionElementId } from './row-focus';

export type TransactionLineProps = {
  transaction: Transaction;
  context: RowContext;
  byDate: boolean;
  onOpen?: (id: string) => void;
  onCategory: (transaction: Transaction) => void;
  onActions?: (transaction: Transaction) => void;
};

export function TransactionLine({
  transaction: row,
  context,
  byDate,
  onOpen,
  onCategory,
  onActions,
}: TransactionLineProps) {
  const theme = useTheme();
  const intl = useIntl();
  const hidden = useAmountsHidden();
  const view = presentTransaction(row, context, intl);
  const day = formatBookingDay(row.bookingDate, intl, byDate);
  const direction = hidden ? null : transactionDirection(row.amount);
  const nativeActions = Platform.OS !== 'web' && onActions ? () => onActions(row) : undefined;
  const actionsLabel = intl.formatMessage({ id: 'account.rowActions' }, { title: view.title });
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
    formatDate(row.bookingDate, intl.locale),
    direction ? intl.formatMessage({ id: direction.messageId }) : null,
    view.purpose,
    view.typeLabel,
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
        nativeID={transactionElementId(row.id)}
        accessibilityRole={onOpen || nativeActions ? 'button' : undefined}
        accessibilityLabel={label}
        disabled={!onOpen && !nativeActions}
        onLongPress={nativeActions}
        accessibilityActions={
          nativeActions ? [{ name: 'rowActions', label: actionsLabel }] : undefined
        }
        onAccessibilityAction={(event) => {
          if (event.nativeEvent.actionName === 'rowActions') nativeActions?.();
        }}
        onPress={onOpen ? () => onOpen(row.id) : undefined}
        style={{
          minHeight: 44,
          flexDirection: 'row',
          justifyContent: 'space-between',
          gap: Spacing.two,
          alignItems: 'center',
        }}
      >
        <View
          // Full dates ("01.10.2026") under the amount sort need more room than day labels.
          style={{
            flexBasis: byDate ? 68 : 96,
            flexShrink: 1,
            flexDirection: 'row',
            gap: Spacing.one,
          }}
        >
          <Text style={{ ...muted, flexShrink: 1 }}>{day}</Text>
          {direction ? (
            <Text style={muted} aria-hidden>
              {direction.icon}
            </Text>
          ) : null}
        </View>
        <View style={{ flex: 1, gap: Spacing.one }}>
          <Text
            numberOfLines={view.titleIsPurpose ? 2 : 1}
            ellipsizeMode="tail"
            style={{ ...TypeScale.bodyMedium, color: theme.textPrimary, fontWeight: '600' }}
          >
            {view.title}
          </Text>
          {view.purpose ? (
            <Text numberOfLines={2} ellipsizeMode="tail" style={muted}>
              {view.purpose}
            </Text>
          ) : null}
          <Text style={muted}>{view.typeLabel}</Text>
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
      {Platform.OS === 'web' && onActions ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={actionsLabel}
          onPress={() => onActions(row)}
          style={{
            minWidth: 44,
            minHeight: 44,
            alignSelf: 'flex-end',
            justifyContent: 'center',
            alignItems: 'center',
          }}
        >
          <Text style={{ color: theme.brandLink }} aria-hidden>
            ⋯
          </Text>
        </Pressable>
      ) : null}
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
