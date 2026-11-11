import { useIntl } from 'react-intl';
import { View } from 'react-native';
import Svg, { Circle, Line } from 'react-native-svg';

import type { components } from '@/api/schema';
import { Amount, amountTexts } from '@/components/shared/amount';
import { formatDate } from '@/format/date';
import { msg, InfoText } from '@/features/overview/overview-text';
import { useTheme } from '@/hooks/use-theme';

export type ValueHistory = components['schemas']['ValueHistoryResponse'];

const width = 360;
const height = 120;
const padding = 5;

/** Convert server values to drawing coordinates only. Labels use the original decimal strings. */
export function chartPoints(points: ValueHistory['points']) {
  const samples = points.flatMap((point) => {
    if (
      typeof point?.date !== 'string' ||
      typeof point.value !== 'string' ||
      !/^-?\d+(?:\.\d+)?$/.test(point.value)
    )
      return [];
    const time = Date.parse(point.date);
    const value = Number(point.value);
    return Number.isFinite(time) && Number.isFinite(value) ? [{ point, time, value }] : [];
  });
  if (samples.length === 0) return [];
  const first = samples[0].time;
  const last = samples[samples.length - 1].time;
  const values = samples.map(({ value }) => value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  return samples.map(({ point, time, value }) => ({
    point,
    x:
      last === first
        ? width / 2
        : padding + ((time - first) / (last - first)) * (width - 2 * padding),
    y:
      max === min
        ? height / 2
        : height - padding - ((value - min) / (max - min)) * (height - 2 * padding),
  }));
}

export function ValueChart({ data, hidden }: { data: ValueHistory; hidden: boolean }) {
  const intl = useIntl();
  const theme = useTheme();
  const coordinates = chartPoints(data.points);
  if (!coordinates.length) return <InfoText>{msg(intl, 'history.empty')}</InfoText>;

  const first = coordinates[0];
  const last = coordinates[coordinates.length - 1];
  const date = (point: typeof first.point) => formatDate(point.date, intl.locale);
  const start = date(first.point);
  const end = date(last.point);
  const amount = (value: string, signRole?: 'change') =>
    amountTexts(intl, { value, currency: data.currency, signRole }).label;
  const estimated = coordinates.some(({ point }) => point.estimated);
  const label = hidden
    ? msg(intl, 'history.chartHidden')
    : msg(intl, 'history.chartSummary', {
        start,
        end,
        startValue: amount(first.point.value),
        endValue: amount(last.point.value),
        change: amount(data.change.amount, 'change'),
      });

  return (
    <>
      <View
        accessible
        accessibilityRole="image"
        accessibilityLabel={
          estimated ? msg(intl, 'history.chartEstimated', { chart: label }) : label
        }
      >
        <View
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          <Svg
            width="100%"
            height={height}
            viewBox={`0 0 ${width} ${height}`}
            preserveAspectRatio="none"
          >
            {coordinates.slice(1).map((end, index) => {
              const start = coordinates[index];
              return (
                <Line
                  key={end.point.date}
                  x1={start.x}
                  y1={start.y}
                  x2={end.x}
                  y2={end.y}
                  stroke={theme.chart1}
                  strokeWidth={2}
                  strokeDasharray={start.point.estimated || end.point.estimated ? '4 3' : undefined}
                />
              );
            })}
            {coordinates.length === 1 ? (
              <Circle cx={first.x} cy={first.y} r={3} fill={theme.chart1} />
            ) : null}
          </Svg>
          {!hidden ? (
            <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
              <View style={{ flexShrink: 1 }}>
                <InfoText>{start}</InfoText>
                <Amount value={first.point.value} currency={data.currency} />
              </View>
              {end !== start ? (
                <View style={{ flexShrink: 1, alignItems: 'flex-end' }}>
                  <InfoText>{end}</InfoText>
                  <Amount value={last.point.value} currency={data.currency} />
                </View>
              ) : null}
            </View>
          ) : null}
        </View>
      </View>
      {estimated ? <InfoText>{msg(intl, 'history.estimated')}</InfoText> : null}
    </>
  );
}
