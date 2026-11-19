import { useId } from 'react';
import { useIntl, type IntlShape } from 'react-intl';
import { Platform, Pressable, Text, View } from 'react-native';

import { Amount, Figure, ValueStatusBadge, amountTexts, isValueKnown } from '@/components/shared';
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

function HoldingsReturn({ account }: { account: PortfolioAccount }) {
  const intl = useIntl();
  const theme = useTheme();
  if (account.totalReturn != null) {
    return (
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.one }}>
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {msg(intl, 'portfolio.unrealisedGain')}
        </Text>
        <Amount
          value={account.totalReturn}
          currency={account.currency}
          signRole="change"
          style={TypeScale.caption}
        />
        {account.totalReturnPercent != null ? (
          <Figure
            value={account.totalReturnPercent}
            kind="percent"
            percentFractionDigits={2}
            signRole="change"
            tone="gainLoss"
            style={TypeScale.caption}
          />
        ) : null}
      </View>
    );
  }

  const reasons: string[] = [];
  if (account.balance == null) {
    reasons.push(
      msg(
        intl,
        account.positionsAsOf == null ? 'portfolio.gain.noHoldings' : 'portfolio.gain.missingValue',
      ),
    );
  }
  if (account.missingCostBasisCount != null) {
    reasons.push(
      msg(intl, 'portfolio.gain.missingBasis', { count: account.missingCostBasisCount }),
    );
  }
  if (account.foreignCurrencyCostBasisCount != null) {
    reasons.push(
      msg(intl, 'portfolio.gain.foreignBasis', { count: account.foreignCurrencyCostBasisCount }),
    );
  }
  if (reasons.length === 0) return null;
  return (
    <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
      {msg(intl, 'portfolio.gain.unavailable', { reason: reasons.join(' · ') })}
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
  const descriptionId = useId();
  const web = Platform.OS === 'web';
  const known = account.valueKnown && isValueKnown(account.valueStatus);
  const detail = rowDetail(intl, account, section);
  const balance = {
    value: account.balance,
    currency: account.currency,
    valueKnown: known,
    masked: hidden,
  };
  const conversion =
    known && account.currency !== portfolioCurrency && account.valueInPortfolioCurrency !== null
      ? {
          ...balance,
          value: account.valueInPortfolioCurrency,
          currency: portfolioCurrency,
          approx: true,
        }
      : null;
  const balanceText = amountTexts(intl, balance);
  const conversionText = conversion ? amountTexts(intl, conversion) : null;
  const spokenAmounts = [balanceText.label, hidden ? null : conversionText?.label]
    .filter(Boolean)
    .join(', ');
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
        // On web, the button name comes from the visible row content in reading order (WCAG 2.5.3)
        // and the spoken amounts become its description.
        accessibilityLabel={web ? undefined : [account.name, spokenAmounts, detail].join(', ')}
        aria-describedby={web ? descriptionId : undefined}
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
              {...balance}
              aria-label={web ? balanceText.display : undefined}
              style={{
                ...TypeScale.numberLarge,
                // With "Hide amounts" a red figure would reveal the sign (as ChangeChip).
                color:
                  known && !hidden && isNegativeAmount(account.balance)
                    ? theme.statusLoss
                    : theme.textPrimary,
              }}
            />
            {conversion ? (
              <Amount
                {...conversion}
                aria-label={web ? conversionText?.display : undefined}
                style={TypeScale.caption}
              />
            ) : null}
          </View>
        </View>
      </Pressable>
      {web ? (
        <Text nativeID={descriptionId} style={{ display: 'none' }}>
          {spokenAmounts}
        </Text>
      ) : null}
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
        {section.section === 'DEPOT' || section.section === 'CRYPTO' ? (
          <HoldingsReturn account={account} />
        ) : null}
      </View>
      {hasPositions(account, section) ? (
        <DepotPositions account={account} onAccount={onAccount} />
      ) : null}
    </View>
  );
}
