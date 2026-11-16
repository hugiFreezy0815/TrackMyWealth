import { useIntl, type IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { Amount, Figure, ValueStatusBadge, amountTexts } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { isNegativeAmount } from '@/features/overview/overview-model';
import { msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import { DepotPositions } from './depot-positions';
import {
  accountTypeMessageKey,
  daysUntil,
  hasPositions,
  type PortfolioAccount,
  type PortfolioSection,
} from './model';

const date = (intl: IntlShape, value: string) => formatDate(value, intl.locale);
/** Test hook only, never shown or announced. */
const rowTestId = (accountId: string) => `account-row-${accountId}`;

/** Row line 2 per section type (spec S2, "Row display per section"). */
export function rowDetail(
  intl: IntlShape,
  account: PortfolioAccount,
  section: PortfolioSection,
): string {
  const parts: string[] = [];
  const holdsPositions = section.section === 'DEPOT' || section.section === 'CRYPTO';
  if (holdsPositions && account.positionsCount != null) {
    parts.push(msg(intl, 'portfolio.depot.positions', { count: account.positionsCount }));
    if (account.positionsAsOf) {
      parts.push(msg(intl, 'portfolio.asOf', { date: date(intl, account.positionsAsOf) }));
    }
    return parts.join(' · ');
  }
  if (section.section === 'PROPERTY_AND_MORTGAGE' && account.accountType === 'CUSTOM_ASSET') {
    return account.valueSourceDate
      ? msg(intl, 'portfolio.account.manual', { date: date(intl, account.valueSourceDate) })
      : msg(intl, 'portfolio.account.manualUndated');
  }
  parts.push(msg(intl, accountTypeMessageKey(account.accountType)));
  if (account.maskedIdentifier) parts.push(account.maskedIdentifier);
  if (section.section === 'PILLAR_3A' || section.section === 'PENSION') {
    if (account.valueSourceDate) {
      parts.push(msg(intl, 'portfolio.asOf', { date: date(intl, account.valueSourceDate) }));
    }
  }
  if (account.statementDate) {
    parts.push(msg(intl, 'portfolio.card.statement', { date: date(intl, account.statementDate) }));
  }
  return parts.join(' · ');
}

function DueChip({ dueDate }: { dueDate: string }) {
  const intl = useIntl();
  const theme = useTheme();
  // Calendar days from today: date arithmetic for the chip, never a money figure.
  const days = daysUntil(dueDate, new Date());
  if (days === null) return null;
  return (
    <Text
      style={{
        ...TypeScale.caption,
        color: days < 0 ? theme.statusWarning : theme.textSecondary,
      }}
    >
      {days < 0
        ? msg(intl, 'portfolio.card.overdue', { date: date(intl, dueDate) })
        : msg(intl, 'portfolio.card.due', { count: days })}
    </Text>
  );
}

export function PortfolioAccountRow({
  account,
  section,
  nested,
  portfolioCurrency,
  onAccount,
}: {
  account: PortfolioAccount;
  section: PortfolioSection;
  /** A mortgage indented under its property (only when that property is in the section). */
  nested: boolean;
  portfolioCurrency: string;
  onAccount: (accountId: string, tab?: 'positions') => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const known = account.valueKnown && account.valueStatus !== 'UNKNOWN';
  const detail = rowDetail(intl, account, section);
  const spokenAmount = amountTexts(intl, {
    value: account.balance,
    currency: account.currency,
    valueKnown: known,
    masked: hidden,
  }).label;
  return (
    <View
      testID={rowTestId(account.accountId)}
      style={{
        paddingStart: nested ? Spacing.three : 0,
        gap: Spacing.two,
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
      }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={[account.name, spokenAmount, detail].join(', ')}
        onPress={() => onAccount(account.accountId)}
        style={{ minHeight: 56, paddingVertical: Spacing.two, gap: Spacing.one }}
      >
        <View
          style={{
            flexDirection: 'row',
            gap: Spacing.two,
            flexWrap: 'wrap',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
          }}
        >
          <View style={{ flex: 1, minWidth: 120, gap: Spacing.one }}>
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
              {account.name}
            </Text>
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{detail}</Text>
          </View>
          <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
            <Amount
              value={account.balance}
              currency={account.currency}
              valueKnown={known}
              style={{
                ...TypeScale.numberLarge,
                // With "Hide amounts" a red figure would reveal the sign (as ChangeChip).
                color:
                  !hidden && isNegativeAmount(account.balance)
                    ? theme.statusLoss
                    : theme.textPrimary,
              }}
            />
            {account.currency !== portfolioCurrency && account.valueInPortfolioCurrency !== null ? (
              <Amount
                approx
                value={account.valueInPortfolioCurrency}
                valueKnown={known}
                currency={portfolioCurrency}
                style={TypeScale.caption}
              />
            ) : null}
          </View>
        </View>
      </Pressable>
      <View
        style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
      >
        <ValueStatusBadge status={account.valueStatus} valueSourceDate={account.valueSourceDate} />
        {account.reconciliationStatus === 'RECONCILED' ? (
          <Text style={{ ...TypeScale.caption, color: theme.statusGain }}>
            {msg(intl, 'portfolio.reconciled')}
          </Text>
        ) : null}
        {section.section === 'CARDS' && account.dueDate ? (
          <DueChip dueDate={account.dueDate} />
        ) : null}
        {account.totalReturn != null ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.one }}>
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
              {msg(intl, 'portfolio.totalReturn')}
            </Text>
            <Figure
              value={account.totalReturn}
              kind="percent"
              signRole="change"
              tone="gainLoss"
              style={TypeScale.caption}
            />
          </View>
        ) : null}
      </View>
      {hasPositions(account, section) ? (
        <DepotPositions account={account} onAccount={onAccount} />
      ) : null}
    </View>
  );
}
