import type { ReactNode } from 'react';
import type { IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { Amount, SectionCard, ValueStatusBadge } from '@/components/shared';
import { amountTexts } from '@/components/shared/amount';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { barWeights, isNegativeAmount, largestLiabilities, type NetWorth } from './overview-model';
import { InfoText, msg } from './overview-text';

export type FinancialPart = 'netWorth' | 'assets' | 'liabilities';

type CardProps = {
  data: NetWorth;
  hidden: boolean;
  explain: (part: FinancialPart) => void;
  intl: IntlShape;
};

/** "Net worth, 478160.56 Swiss francs, estimated": label, spoken amount, status. */
function spokenTotal(
  intl: IntlShape,
  labelId: string,
  value: string,
  currency: string,
  hidden: boolean,
  estimated = false,
): string {
  return [
    msg(intl, labelId),
    amountTexts(intl, { value, currency, masked: hidden }).label,
    estimated ? msg(intl, 'valueStatus.estimated') : '',
  ]
    .filter(Boolean)
    .join(', ');
}

/** S1 #6/#7: net worth with its status. A negative figure keeps its sign and the loss colour. */
export function NetWorthCard({
  data,
  hidden,
  explain,
  intl,
  compact = false,
  children,
}: CardProps & { compact?: boolean; children?: ReactNode }) {
  const theme = useTheme();
  return (
    <SectionCard title={msg(intl, 'overview.netWorth')}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={spokenTotal(
          intl,
          'overview.netWorth',
          data.netWorth,
          data.currency,
          hidden,
          data.approximate,
        )}
        accessibilityHint={msg(intl, 'overview.explain')}
        onPress={() => explain('netWorth')}
        style={{ minHeight: 44, justifyContent: 'center' }}
      >
        <Amount
          testID="overview-net-worth"
          value={data.netWorth}
          currency={data.currency}
          masked={hidden}
          style={{
            ...(compact ? TypeScale.headingLarge : TypeScale.displayMedium),
            color: isNegativeAmount(data.netWorth) ? theme.statusLoss : theme.textPrimary,
          }}
        />
      </Pressable>
      {data.approximate ? <ValueStatusBadge status="ESTIMATED" /> : null}
      {!data.complete ? (
        <Pressable
          accessibilityRole="button"
          accessibilityHint={msg(intl, 'overview.explain')}
          onPress={() => explain('netWorth')}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
        </Pressable>
      ) : null}
      {children}
    </SectionCard>
  );
}

/** S1 #15: "Includes Mortgage (CHF 600'000.00) and DKB Visa (…)", two largest first. */
function IncludesLine({ data, hidden, intl }: Omit<CardProps, 'explain'>) {
  const theme = useTheme();
  const liabilities = largestLiabilities(data);
  if (liabilities.length === 0) return null;
  const items = liabilities.slice(0, 2).map((account) => {
    const texts = amountTexts(intl, {
      value: account.value,
      currency: account.currency,
      masked: hidden,
    });
    return {
      display: msg(intl, 'overview.liabilities.item', {
        name: account.name,
        amount: texts.display,
      }),
      spoken: msg(intl, 'overview.liabilities.item', { name: account.name, amount: texts.label }),
    };
  });
  const more = liabilities.length - items.length;
  const id =
    items.length === 1
      ? 'overview.liabilities.includesOne'
      : more > 0
        ? 'overview.liabilities.includesMore'
        : 'overview.liabilities.includes';
  const sentence = (key: 'display' | 'spoken') =>
    msg(intl, id, { first: items[0][key], second: items[1]?.[key] ?? '', count: more });
  return (
    <Text
      testID="overview-liabilities-includes"
      accessibilityLabel={sentence('spoken')}
      style={{ ...TypeScale.caption, color: theme.textSecondary }}
    >
      {sentence('display')}
    </Text>
  );
}

/** S1 #12–#15: assets and liabilities side by side, the decorative bar, "Includes …". */
export function AssetsLiabilitiesCard({ data, hidden, explain, intl }: CardProps) {
  const theme = useTheme();
  const weights = barWeights(data.totalAssets, data.totalLiabilities);
  const total = (part: 'assets' | 'liabilities') => {
    const value = part === 'assets' ? data.totalAssets : data.totalLiabilities;
    const labelId = part === 'assets' ? 'overview.assets' : 'overview.liabilities';
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={spokenTotal(intl, labelId, value, data.currency, hidden)}
        accessibilityHint={msg(intl, 'overview.explain')}
        onPress={() => explain(part)}
        style={{
          minHeight: 44,
          justifyContent: 'center',
          alignItems: part === 'assets' ? 'flex-start' : 'flex-end',
          // Both grow: wrapped onto its own line, Liabilities stays right-aligned.
          flexGrow: 1,
          flexShrink: 1,
          gap: 2,
        }}
      >
        <InfoText>{msg(intl, labelId)}</InfoText>
        <Amount
          value={value}
          currency={data.currency}
          masked={hidden}
          style={{
            ...TypeScale.headingMedium,
            color: part === 'liabilities' ? theme.statusLoss : theme.textPrimary,
          }}
        />
      </Pressable>
    );
  };
  // M03: no card heading; the "Assets" and "Liabilities" labels carry the meaning.
  return (
    <View
      testID="overview-assets-liabilities"
      style={{
        padding: Spacing.three,
        gap: Spacing.compact,
        borderWidth: 1,
        borderColor: theme.borderDefault,
        borderRadius: Radii.card,
        backgroundColor: theme.bgSurface,
      }}
    >
      <View
        style={{
          flexDirection: 'row',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
          gap: Spacing.two,
        }}
      >
        {total('assets')}
        {total('liabilities')}
      </View>
      {weights ? (
        <View
          testID="overview-assets-liabilities-bar"
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={{ flexDirection: 'row', height: 8, borderRadius: Radii.pill, overflow: 'hidden' }}
        >
          <View style={{ flexGrow: weights.assets, backgroundColor: theme.statusGain }} />
          <View style={{ flexGrow: weights.liabilities, backgroundColor: theme.statusLoss }} />
        </View>
      ) : null}
      <IncludesLine data={data} hidden={hidden} intl={intl} />
    </View>
  );
}
