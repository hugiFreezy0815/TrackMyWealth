import { useState, type ReactNode } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  ActionButton,
  Amount,
  Figure,
  SectionCard,
  ValueStatusBadge,
  isValueKnown,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { InfoText, msg } from '@/features/overview/overview-text';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';

import type { investmentFigures } from './model';

type Performance = NonNullable<ReturnType<typeof investmentFigures>['performance']>;
type MarketValue = NonNullable<ReturnType<typeof investmentFigures>['value']>;

function ReturnFigures({ data, currency }: { data: Performance; currency: string }) {
  return (
    <View
      style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', gap: Spacing.two }}
    >
      <Amount value={data.amount} currency={currency} signRole="change" />
      {data.percent != null ? (
        <Figure value={data.percent} kind="percent" signRole="change" tone="gainLoss" />
      ) : null}
    </View>
  );
}

function CostBasisNotes({ data }: { data: Performance }) {
  const intl = useIntl();
  const missing = data.missingCostBasisCount;
  const foreign = data.foreignCurrencyCostBasisCount;
  return (
    <>
      {missing ? (
        <InfoText warning>
          {msg(intl, 'investments.performance.missingBasis', { count: missing })}
        </InfoText>
      ) : null}
      {foreign ? (
        <InfoText warning>
          {msg(intl, 'investments.performance.foreignBasis', { count: foreign })}
        </InfoText>
      ) : null}
    </>
  );
}

export function PerformanceCard({
  data,
  currency,
  onOpen,
}: {
  data: Performance;
  currency: string;
  onOpen: () => void;
}) {
  const intl = useIntl();
  return (
    <SectionCard title={msg(intl, 'investments.performance')}>
      <InfoText>{msg(intl, 'investments.performance.totalReturn')}</InfoText>
      <ReturnFigures data={data} currency={currency} />
      <CostBasisNotes data={data} />
      <ActionButton label={msg(intl, 'investments.performance.open')} onPress={onOpen} />
    </SectionCard>
  );
}

const BREAKDOWN = ['invested', 'unrealised', 'realised', 'income', 'fees', 'taxes', 'mwr'] as const;

function PerformanceLine({
  name,
  reason,
  children,
}: {
  name: (typeof BREAKDOWN)[number] | 'totalReturn' | 'returnPercent' | 'marketValue';
  reason?: string;
  children?: ReactNode;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  const label = msg(intl, 'investments.performance.' + name);
  return (
    <View
      style={{
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
        paddingBottom: Spacing.two,
      }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={msg(intl, 'investments.explain', { label })}
        aria-expanded={expanded}
        onPress={() => setExpanded((previous) => !previous)}
        style={{ minHeight: 44, justifyContent: 'center' }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>{label}</Text>
      </Pressable>
      {children ?? <Text style={{ color: theme.textPrimary }}>{'–'}</Text>}
      {reason ? <InfoText>{reason}</InfoText> : null}
      {expanded ? <InfoText>{msg(intl, 'investments.explanation.' + name)}</InfoText> : null}
    </View>
  );
}

export function PerformanceBreakdown({ data, value }: { data: Performance; value: MarketValue }) {
  const intl = useIntl();
  const unavailable = msg(intl, 'investments.performance.unavailable');
  const valueKnown = value.known && isValueKnown(value.status);
  const hasCostBasisGap = data.missingCostBasisCount || data.foreignCurrencyCostBasisCount;
  const returnReason = hasCostBasisGap
    ? msg(intl, 'investments.performance.requiresGain')
    : unavailable;
  return (
    <>
      {data.asOf ? (
        <InfoText>
          {msg(intl, 'overview.explain.asOf', { date: formatDate(data.asOf, intl.locale) })}
        </InfoText>
      ) : null}
      <PerformanceLine name="totalReturn" reason={data.amount == null ? returnReason : undefined}>
        <Amount value={data.amount} currency={value.currency} signRole="change" />
      </PerformanceLine>
      <PerformanceLine
        name="returnPercent"
        reason={
          data.percent == null ? msg(intl, 'investments.performance.percentUnavailable') : undefined
        }
      >
        <Figure value={data.percent} kind="percent" signRole="change" tone="gainLoss" />
      </PerformanceLine>
      <PerformanceLine
        name="marketValue"
        reason={value.amount == null || !valueKnown ? unavailable : undefined}
      >
        <Amount value={value.amount} currency={value.currency} valueKnown={valueKnown} />
        <ValueStatusBadge status={value.status} valueSourceDate={value.asOf} />
        {value.statusUnavailable ? (
          <InfoText>{msg(intl, 'investments.valueStatusUnavailable')}</InfoText>
        ) : null}
      </PerformanceLine>
      {BREAKDOWN.map((name) => (
        <PerformanceLine key={name} name={name} reason={unavailable} />
      ))}
      <CostBasisNotes data={data} />
    </>
  );
}
