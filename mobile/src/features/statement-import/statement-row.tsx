import { useIntl } from 'react-intl';
import { Switch, Text, View } from 'react-native';

import type { components } from '@/api/schema';
import { LinkPocket } from '@/features/savings-pocket/link-pocket';
import { Amount, amountTexts } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';

import { canToggleRow, type ImportRow } from './model';
import { transactionTypeLabel, useText } from './statement-text';

type Props = {
  row: ImportRow;
  account?: components['schemas']['AccountSummaryResponse'];
  /** Switches are off while a write runs or the device is offline. */
  disabled: boolean;
  onSwitch: (row: ImportRow, included: boolean) => void;
};

/** One `ImportRowResponse` of the review (S7 step 2, "Row"). */
export function StatementRow({ row, disabled, onSwitch, account }: Props) {
  const intl = useIntl();
  const t = useText();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const canonical = row.canonical;
  const description =
    canonical?.description ?? canonical?.counterpartyName ?? t('statement.row.unknown');
  // Text of an amount for a sentence or a label; "Hide amounts" masks it there too.
  const amountText = (value: string, currency: string) =>
    amountTexts(intl, { value, currency, masked: hidden }).display;
  const type = canonical ? transactionTypeLabel(intl, canonical.transactionType) : null;
  const subtitle = [
    canonical?.bookingDate ? formatDate(canonical.bookingDate, intl.locale) : null,
    type,
    t(`statement.filter.${row.status}`),
  ].filter(Boolean);

  return (
    <View
      style={{
        gap: Spacing.one,
        paddingVertical: Spacing.two,
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
      }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{description}</Text>
      <Text style={{ color: theme.textSecondary }}>{subtitle.join(' · ')}</Text>
      {canonical ? <Amount value={canonical.amount} currency={canonical.currency} /> : null}
      {row.duplicateOf ? (
        <Text style={{ color: theme.textSecondary }}>
          {t('statement.row.duplicate', {
            date: formatDate(row.duplicateOf.bookingDate, intl.locale),
            name: row.duplicateOf.merchantDescription ?? '',
            amount: amountText(row.duplicateOf.amount, row.duplicateOf.currency),
          })}
        </Text>
      ) : null}
      {row.recheck ? (
        <Text style={{ color: theme.statusWarning }}>
          {'⚠ '}
          {t(`statement.row.recheck.${row.recheck}`)}
        </Text>
      ) : null}
      {row.warnings.map((warning, index) => (
        <Text key={`${warning.code}-${index}`} style={{ color: theme.statusWarning }}>
          {'⚠ '}
          {warning.message}
        </Text>
      ))}
      {row.warnings.some(({ code }) => code === 'IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE') ? (
        <LinkPocket account={account} disabled={disabled} />
      ) : null}
      {row.error ? (
        <Text style={{ color: theme.statusLoss }}>
          {'⛔ '}
          {row.error.message}
        </Text>
      ) : null}
      {canToggleRow(row) ? (
        <View
          style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}
        >
          <Text style={{ color: theme.textPrimary }}>
            {t(row.included ? 'statement.row.include' : 'statement.row.skip')}
          </Text>
          <Switch
            value={row.included}
            disabled={disabled}
            accessibilityLabel={t('statement.row.toggle', {
              name: description,
              amount: canonical ? amountText(canonical.amount, canonical.currency) : '',
            })}
            onValueChange={(included) => onSwitch(row, included)}
          />
        </View>
      ) : null}
    </View>
  );
}
