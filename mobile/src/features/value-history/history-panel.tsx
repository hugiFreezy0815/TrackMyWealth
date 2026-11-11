import { useIntl } from 'react-intl';
import { View } from 'react-native';

import { Amount, ChangeChip, ChipGroup, ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { figureTexts } from '@/components/shared/figure';
import { Spacing } from '@/constants/theme';
import { InfoText, msg } from '@/features/overview/overview-text';
import { periods, selectHistoryPeriod, type HistoryPeriod } from './period';
import { ValueChart, type ValueHistory } from './value-chart';

const attribution = [
  ['contributions', 'history.saving'],
  ['capitalMovements', 'history.capitalMovements'],
  ['marketAndFx', 'history.marketAndFx'],
] as const;

export function HistoryPanel({
  query,
  period,
  hidden,
}: {
  query: {
    data?: ValueHistory;
    isPending: boolean;
    isError: boolean;
    error: unknown;
    refetch: () => unknown;
  };
  period: HistoryPeriod;
  hidden: boolean;
}) {
  const intl = useIntl();
  const data = query.data?.period === period ? query.data : undefined;
  const percentage =
    data?.change.percent != null && !hidden
      ? figureTexts(intl, { value: data.change.percent, kind: 'percent', signRole: 'change' })
      : null;
  return (
    <>
      <ChipGroup
        label={msg(intl, 'history.period')}
        options={periods.map((value) => {
          const label = msg(intl, `history.period.${value}`);
          return {
            value,
            label,
            accessibilityLabel: msg(intl, 'history.periodOption', {
              label,
              range: msg(intl, `history.range.${value}`),
            }),
          };
        })}
        selected={[period]}
        onSelect={(value) => {
          const option = periods.find((candidate) => candidate === value);
          if (option) selectHistoryPeriod(option);
        }}
      />
      {query.isPending ? <LoadingSkeleton count={1} height={120} /> : null}
      {query.isError ? (
        <ErrorBanner error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {data ? (
        <>
          {data.points.length > 0 ? (
            <>
              <InfoText>{msg(intl, `history.range.${period}`)}</InfoText>
              <ChangeChip
                amount={data.change.amount}
                currency={data.currency}
                percentage={percentage?.display}
                percentageLabel={percentage?.label}
              />
              {attribution.map(([field, label]) => {
                const value = data.change[field];
                if (value == null) return null;
                return (
                  <View
                    key={field}
                    style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.one }}
                  >
                    <InfoText>{msg(intl, label)}</InfoText>
                    <Amount
                      value={value}
                      currency={data.currency}
                      masked={hidden}
                      signRole="change"
                    />
                  </View>
                );
              })}
            </>
          ) : null}
          <ValueChart data={data} hidden={hidden} />
          {!data.complete || data.warnings.length > 0 ? (
            <InfoText warning>{msg(intl, 'history.incomplete')}</InfoText>
          ) : null}
        </>
      ) : null}
    </>
  );
}
