import type { IntlShape } from 'react-intl';
import { Text, View } from 'react-native';

import { Amount, SectionCard } from '@/components/shared';
import { amountTexts } from '@/components/shared/amount';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { currencyAmount, hasNonZeroAmount, type CashFlow } from './overview-model';
import { InfoText, msg } from './overview-text';

const ENTRY_LABELS = [
  ['income', 'overview.cashFlow.income'],
  ['spending', 'overview.cashFlow.spending'],
  ['saving', 'overview.cashFlow.saving'],
] as const;

/** S1 #23–#26: the server's entries for the scope currency; a missing one shows "–". */
export function CashFlowCard({
  data,
  currency,
  hidden,
  intl,
}: {
  data: CashFlow;
  currency: string;
  hidden: boolean;
  intl: IntlShape;
}) {
  const theme = useTheme();
  const month = intl.formatDate(new Date(data.month + '-01T12:00:00Z'), {
    month: 'long',
    timeZone: 'UTC',
  });
  const pending = currencyAmount(data.pendingReview, currency);
  return (
    <SectionCard title={msg(intl, 'overview.cashFlow.title', { month })}>
      {ENTRY_LABELS.map(([field, label]) => {
        const entry = currencyAmount(data[field], currency);
        return (
          <View
            key={field}
            style={{
              flexDirection: 'row',
              flexWrap: 'wrap',
              justifyContent: 'space-between',
              gap: 8,
            }}
          >
            <InfoText>{msg(intl, label)}</InfoText>
            <Amount
              value={entry?.amount ?? null}
              valueKnown={entry?.valueKnown ?? false}
              currency={currency}
              masked={hidden}
              style={TypeScale.numberLarge}
            />
          </View>
        );
      })}
      {pending?.valueKnown && hasNonZeroAmount(pending.amount) ? (
        <View style={{ padding: Spacing.compact, backgroundColor: theme.statusWarningSubtle }}>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.statusWarning }}>
            {msg(intl, 'overview.cashFlow.pendingReview', {
              amount: amountTexts(intl, { value: pending.amount, currency, masked: hidden })
                .display,
            })}
          </Text>
        </View>
      ) : null}
      {!data.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
    </SectionCard>
  );
}
