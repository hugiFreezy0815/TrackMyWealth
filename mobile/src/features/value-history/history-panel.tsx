import { Fragment } from 'react';
import { useIntl } from 'react-intl';
import { Text, View } from 'react-native';

import {
  Amount,
  ChangeChip,
  ChipGroup,
  directionOf,
  ErrorBanner,
  LoadingSkeleton,
  type ChangeFigure,
} from '@/components/shared';
import { figureTexts } from '@/components/shared/figure';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { InfoText, msg } from '@/features/overview/overview-text';
import { useTheme } from '@/hooks/use-theme';
import { isHistoryPeriod, periods, selectHistoryPeriod, type HistoryPeriod } from './period';
import { changeIsUncertain, historyWarningMessageIds } from './warnings';
import { ValueChart, type ValueHistory } from './value-chart';

const attribution = [
  ['contributions', 'history.saving'],
  ['capitalMovements', 'history.capitalMovements'],
  ['marketAndFx', 'history.marketAndFx'],
] as const;

export function historyForDisplay(
  query: { data?: ValueHistory; isPlaceholderData: boolean },
  period: HistoryPeriod,
  online: boolean,
) {
  const data = query.data;
  if (data?.period === period) return data;
  if (!online && query.isPlaceholderData && isHistoryPeriod(data?.period)) return data;
}

/**
 * #607: "Return +0.7%" for the chip, from the server's period return. Never derived here; absent
 * when the server sends none or amounts are hidden (a percentage would hint at the amounts).
 */
function returnFigure(
  intl: ReturnType<typeof useIntl>,
  data: ValueHistory | undefined,
  hidden: boolean,
): ChangeFigure | null {
  const value = data?.change.returnPercent;
  if (value == null || hidden) return null;
  const percent = figureTexts(intl, { value, kind: 'percent', signRole: 'change' });
  return {
    text: msg(intl, 'history.return', { percent: percent.display }),
    label: msg(intl, 'history.return', { percent: percent.label }),
    direction: directionOf(percent.display),
  };
}

/** The chip's period: "since 17 Oct 2026" when the return starts after the period does. */
function chipRange(intl: ReturnType<typeof useIntl>, data: ValueHistory, figure: unknown) {
  const returnFrom = data.change.returnFrom;
  return figure && returnFrom && returnFrom > data.from
    ? msg(intl, 'history.chipSince', { date: formatDate(returnFrom, intl.locale) })
    : msg(intl, `history.chipRange.${data.period}`);
}

/** "Net saving +CHF 3'635.70 · Market & FX +CHF 3'206.40" on one wrapping line (M03). */
function ChangeSplit({ data, hidden }: { data: ValueHistory; hidden: boolean }) {
  const intl = useIntl();
  const theme = useTheme();
  const parts = attribution.flatMap(([field, label]) => {
    const value = data.change[field];
    return value == null ? [] : [{ field, label, value }];
  });
  if (parts.length === 0) return null;
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'baseline', gap: 4 }}>
      {parts.map(({ field, label, value }, index) => (
        <Fragment key={field}>
          {index > 0 ? (
            <Text
              accessibilityElementsHidden
              importantForAccessibility="no-hide-descendants"
              style={{ ...TypeScale.caption, color: theme.textSecondary }}
            >
              ·
            </Text>
          ) : null}
          <InfoText>{msg(intl, label)}</InfoText>
          <Amount
            value={value}
            currency={data.currency}
            masked={hidden}
            signRole="change"
            style={{ ...TypeScale.caption, color: theme.textSecondary }}
          />
        </Fragment>
      ))}
    </View>
  );
}

/**
 * S1 #8–#11 / S2 #9 in the M03 order: change chip with its period, the split, the chart, then the
 * period switch. Warnings and the offline/loading states sit above the switch.
 */
export function HistoryPanel({
  query,
  data,
  online,
  period,
  hidden,
}: {
  query: {
    isPending: boolean;
    isPlaceholderData: boolean;
    isError: boolean;
    error: unknown;
    refetch: () => unknown;
  };
  data?: ValueHistory;
  online: boolean;
  period: HistoryPeriod;
  hidden: boolean;
}) {
  const intl = useIntl();
  const unavailableOffline = !online && data?.period !== period;
  const codes = data?.warningCodes ?? [];
  const warnings = historyWarningMessageIds(codes);
  const uncertain = changeIsUncertain(codes);
  const figure = returnFigure(intl, data, hidden);
  return (
    <>
      {unavailableOffline ? (
        <View role="status" accessibilityLiveRegion="polite">
          <InfoText>
            {data
              ? msg(intl, 'history.offlineShowing', {
                  requested: msg(intl, `history.period.${period}`),
                  shown: msg(intl, `history.period.${data.period}`),
                })
              : msg(intl, 'history.offlineUnavailable', {
                  requested: msg(intl, `history.period.${period}`),
                })}
          </InfoText>
        </View>
      ) : null}
      {query.isError ? (
        <ErrorBanner error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {online && (query.isPending || query.isPlaceholderData) ? (
        <LoadingSkeleton count={1} height={120} />
      ) : null}
      {data ? (
        <>
          {data.points.length > 0 ? (
            <>
              <View
                style={{
                  flexDirection: 'row',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  gap: Spacing.two,
                }}
              >
                <ChangeChip
                  amount={data.change.amount}
                  currency={data.currency}
                  figure={figure}
                  uncertain={uncertain}
                />
                <InfoText>{chipRange(intl, data, figure)}</InfoText>
              </View>
              {uncertain ? (
                <InfoText warning>
                  {msg(intl, figure ? 'history.changeUncertainReturn' : 'history.changeUncertain')}
                </InfoText>
              ) : null}
              <ChangeSplit data={data} hidden={hidden} />
            </>
          ) : null}
          <ValueChart data={data} hidden={hidden} />
          {!data.complete || codes.length > 0 ? (
            <View role="status" accessibilityLiveRegion="polite" style={{ gap: Spacing.one }}>
              <InfoText warning>{msg(intl, 'history.incomplete')}</InfoText>
              {warnings.map((id) => (
                <InfoText key={id} warning>
                  {msg(intl, id)}
                </InfoText>
              ))}
            </View>
          ) : null}
        </>
      ) : null}
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
          if (isHistoryPeriod(value)) selectHistoryPeriod(value);
        }}
      />
    </>
  );
}
