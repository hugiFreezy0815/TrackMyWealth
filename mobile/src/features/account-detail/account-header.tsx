import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  ActionButton,
  Amount,
  ErrorBanner,
  LoadingSkeleton,
  ValueStatusBadge,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate, formatInstantDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import {
  asOfLine,
  reconciliationLine,
  valueSource,
  type AccountListItem,
  type AccountSummary,
} from './model';

type HeaderProps = {
  summary: AccountSummary;
  value: AccountListItem | undefined;
  valuePending: boolean;
  valueError: unknown;
  onRetryValue: () => void;
  onExplainValue: () => void;
};

/** S3 header card: value, conversion, status, as-of line and reconciliation (rows 3–8). */
export function AccountHeaderCard({
  summary,
  value,
  valuePending,
  valueError,
  onRetryValue,
  onExplainValue,
}: HeaderProps) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <View
      style={{
        padding: Spacing.three,
        backgroundColor: theme.bgSurface,
        borderRadius: 16,
        gap: Spacing.two,
      }}
    >
      <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
        {intl.formatMessage({
          id: summary.holdsPositions
            ? 'account.marketValue'
            : summary.manualValuation
              ? 'account.value'
              : 'account.balance',
        })}
      </Text>
      {value ? (
        <Pressable
          testID="account-value"
          onPress={onExplainValue}
          accessibilityRole="button"
          accessibilityHint={intl.formatMessage({ id: 'account.explain' })}
          style={{ minHeight: 44, justifyContent: 'center', gap: Spacing.one }}
        >
          <Amount
            value={value.balance}
            currency={value.currency}
            valueKnown={value.valueKnown}
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
          />
          {value.currency !== value.workspaceCurrency &&
          value.balanceInWorkspaceCurrency !== null ? (
            <Amount
              value={value.balanceInWorkspaceCurrency}
              currency={value.workspaceCurrency}
              approx
              style={{ color: theme.textSecondary }}
            />
          ) : null}
          <ValueStatusBadge
            status={value.valueStatus}
            valueSourceDate={value.valueSourceDate}
            interactive={false}
          />
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {asOfLine(valueSource(summary), value, intl)}
          </Text>
        </Pressable>
      ) : valuePending ? (
        <LoadingSkeleton count={2} />
      ) : (
        <ErrorBanner error={valueError} onRetry={onRetryValue} />
      )}
      <ReconciliationText summary={summary} />
    </View>
  );
}

function ReconciliationText({ summary }: { summary: AccountSummary }) {
  const intl = useIntl();
  const theme = useTheme();
  const line = reconciliationLine(summary.reconciliation);
  if (!line) return null;
  const color =
    line.tone === 'success'
      ? theme.statusGain
      : line.tone === 'warning'
        ? theme.statusWarning
        : theme.textSecondary;
  return (
    <Text testID="account-reconciliation" style={{ color }}>
      {line.icon ? `${line.icon} ` : ''}
      {intl.formatMessage(
        { id: line.messageId },
        {
          date: line.date ? formatDate(line.date, intl.locale) : '–',
          // The privacy-aware amount, so masking also hides the difference.
          amount: line.difference ? (
            <Amount
              key="difference"
              value={line.difference.value}
              currency={line.difference.currency}
              style={{ color }}
            />
          ) : (
            '–'
          ),
        },
      )}
    </Text>
  );
}

/** Archived state: the banner, the reason changing actions are off, and Restore. */
export function ArchivedBanner({
  archivedAt,
  disabled,
  onRestore,
}: {
  archivedAt: string | null;
  disabled: boolean;
  onRestore: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  return (
    <View
      testID="account-archived"
      style={{
        padding: Spacing.three,
        backgroundColor: theme.statusWarningSubtle,
        gap: Spacing.two,
      }}
    >
      <Text style={{ color: theme.textPrimary }}>
        {intl.formatMessage(
          { id: 'account.archived' },
          { date: archivedAt ? formatInstantDate(archivedAt, intl.locale) : '–' },
        )}
      </Text>
      <Text style={{ color: theme.textSecondary }}>
        {intl.formatMessage({ id: 'account.archivedReason' })}
      </Text>
      <ActionButton
        label={intl.formatMessage({ id: 'account.restore' })}
        disabled={disabled}
        onPress={onRestore}
      />
    </View>
  );
}
