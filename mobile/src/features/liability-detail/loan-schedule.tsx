import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, View } from 'react-native';

import {
  Amount,
  amountTexts,
  BottomSheet,
  ErrorBanner,
  isValidIsoDate,
  LoadingSkeleton,
  RefreshingIndicator,
} from '@/components/shared';
import { formatDate, localDay } from '@/format/date';
import { isPlainDecimal } from '@/format/decimal-parts';
import { isAccessFailure } from '@/features/account-detail/access';
import { InfoText, msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { chartPoints, ValueLine } from '@/features/value-history/value-chart';
import { useTheme } from '@/hooks/use-theme';

import { LiabilityPanel } from './panel';
import { type ScheduleEntry, useLoanSchedule } from './queries';

export function LoanSchedulePanel({ accountId }: { accountId: string }) {
  const intl = useIntl();
  const schedule = useLoanSchedule(accountId);
  const pages = isAccessFailure(schedule.error) ? undefined : schedule.data?.pages;
  const entries = pages?.flatMap(({ content }) => content) ?? [];
  const lastPage = pages?.at(-1)?.page;
  const complete = !!lastPage && lastPage.number + 1 >= lastPage.totalPages;
  const today = localDay(new Date());
  const next = entries.find(({ dueDate }) => isValidIsoDate(dueDate) && dueDate >= today);
  const last = complete ? entries.at(-1) : undefined;
  const usableEnd = last && isValidIsoDate(last.dueDate) && isPlainDecimal(last.remainingBalance);
  const paidOff = !!usableEnd && /^-?0+(\.0+)?$/.test(last.remainingBalance);
  const date = (value: string) => formatDate(value, intl.locale);

  return (
    <LiabilityPanel title={msg(intl, 'loan.nextPayment')}>
      {schedule.isError ? (
        <ErrorBanner
          error={schedule.error}
          onRetry={() => {
            if (schedule.isFetchNextPageError && !isAccessFailure(schedule.error)) {
              void schedule.fetchNextPage({ cancelRefetch: false });
            } else {
              void schedule.refetch();
            }
          }}
        />
      ) : null}
      {schedule.isPending && schedule.isFetching ? <LoadingSkeleton count={2} /> : null}
      {schedule.isPending && schedule.isPaused ? (
        <InfoText>{msg(intl, 'loan.scheduleOffline')}</InfoText>
      ) : null}
      <RefreshingIndicator active={schedule.isRefetching || schedule.isFetchingNextPage} />
      {complete && !entries.length ? <InfoText>{msg(intl, 'loan.noSchedule')}</InfoText> : null}
      {next ? (
        <>
          <InfoText>{date(next.dueDate)}</InfoText>
          <InfoText>
            {intl.formatMessage(
              { id: 'loan.split' },
              {
                principal: (
                  <Amount
                    key="principal"
                    value={next.principalComponent}
                    currency={next.currency}
                  />
                ),
                interest: (
                  <Amount key="interest" value={next.interestComponent} currency={next.currency} />
                ),
              },
            )}
          </InfoText>
          <InfoText>
            {intl.formatMessage(
              { id: 'loan.remainingAfter' },
              {
                amount: (
                  <Amount key="remaining" value={next.remainingBalance} currency={next.currency} />
                ),
              },
            )}
          </InfoText>
        </>
      ) : null}
      {entries.length ? (
        <ScheduleChart entries={entries} paidOff={paidOff} complete={complete} />
      ) : null}
      {pages && !complete ? <InfoText>{msg(intl, 'loan.schedulePartial')}</InfoText> : null}
      {usableEnd ? (
        <InfoText>
          {paidOff
            ? msg(intl, 'loan.paidOffOn', { date: date(last.dueDate) })
            : intl.formatMessage(
                { id: 'loan.scheduleEnds' },
                {
                  date: date(last.dueDate),
                  remaining: (
                    <Amount
                      key="remaining"
                      value={last.remainingBalance}
                      currency={last.currency}
                    />
                  ),
                },
              )}
        </InfoText>
      ) : null}
    </LiabilityPanel>
  );
}

/** Compare chart endpoints at the same decimal scale without rounding the server's amounts. */
function balanceTrend(start: string, end: string) {
  const fractionLength = (value: string) => value.split('.')[1]?.length ?? 0;
  const scale = Math.max(fractionLength(start), fractionLength(end));
  const scaled = (value: string) =>
    BigInt(value.replace('.', '') + '0'.repeat(scale - fractionLength(value)));
  const first = scaled(start);
  const last = scaled(end);
  return last < first ? 'decreases' : last > first ? 'increases' : 'flat';
}

function ScheduleChart({
  entries,
  paidOff,
  complete,
}: {
  entries: ScheduleEntry[];
  paidOff: boolean;
  complete: boolean;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const [explaining, setExplaining] = useState(false);
  const coordinates = chartPoints(
    entries
      .filter(({ dueDate }) => isValidIsoDate(dueDate))
      .map(({ dueDate, remainingBalance }) => ({
        date: dueDate,
        value: remainingBalance,
        estimated: false,
      })),
  );
  const first = coordinates[0]?.point;
  const last = coordinates.at(-1)?.point;
  if (!first || !last) return null;
  // The chart shares the account's currency, which the schedule endpoint repeats on every entry.
  const currency = entries[0].currency;
  const from = formatDate(first.date, intl.locale);
  const to = formatDate(last.date, intl.locale);
  const amountLabel = (value: string) => amountTexts(intl, { value, currency }).label;
  const summary = hidden
    ? msg(intl, 'loan.chartHidden')
    : coordinates.length === 1
      ? msg(intl, 'loan.chartPointSummary', { date: from, amount: amountLabel(first.value) })
      : msg(intl, 'loan.chartSummary', {
          from,
          to,
          start: amountLabel(first.value),
          end: amountLabel(last.value),
          trend: balanceTrend(first.value, last.value),
        });
  const chartLabel = complete ? summary : msg(intl, 'loan.chartPartial', { summary });

  return (
    <>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={chartLabel}
        accessibilityHint={msg(intl, 'loan.chartExplain')}
        onPress={() => setExplaining(true)}
        style={{ minHeight: 44, justifyContent: 'center' }}
      >
        <View
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          {hidden ? (
            <InfoText>{summary}</InfoText>
          ) : (
            <>
              <InfoText>{msg(intl, 'loan.chartTitle')}</InfoText>
              <ValueLine coordinates={coordinates} color={theme.statusLoss} markEnd={paidOff} />
            </>
          )}
        </View>
      </Pressable>
      <BottomSheet
        title={msg(intl, 'loan.chartTitle')}
        visible={explaining}
        onClose={() => setExplaining(false)}
      >
        <InfoText>{msg(intl, 'loan.chartSource', { from, to, currency })}</InfoText>
        <InfoText>{chartLabel}</InfoText>
      </BottomSheet>
    </>
  );
}
