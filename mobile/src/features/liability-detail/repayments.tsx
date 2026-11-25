import { useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { Text, View } from 'react-native';

import { queryKeys } from '@/api/queries/keys';
import {
  ActionButton,
  Amount,
  amountTexts,
  ErrorBanner,
  LoadingSkeleton,
  RefreshingIndicator,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate, localDay } from '@/format/date';
import { isAccessFailure } from '@/features/account-detail/access';
import { transactionTypeLabel } from '@/features/account-detail/model';
import { InfoText, msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import { LiabilityBadge, LiabilityLink, LiabilityPanel } from './panel';
import {
  type Repayment,
  useRecentRepayments,
  useRepaymentHistory,
  useRepaymentYearlyTotals,
} from './queries';
import { useRepaymentHistoryView } from './repayment-history-view';

export type OpenRepaymentTransaction = (id: string, accountId: string) => void;

export function RepaymentsPanel({
  accountId,
  onTransaction,
  inAside = false,
}: {
  accountId: string;
  onTransaction?: OpenRepaymentTransaction;
  /** In the desktop side column a link opens beside the panel, so there is no Back to restore. */
  inAside?: boolean;
}) {
  const intl = useIntl();
  const online = useOnline();
  const queryClient = useQueryClient();
  const { view, setView, saveForBack } = useRepaymentHistoryView(accountId);
  const { showAll, expandedIds } = view;
  const recent = useRecentRepayments(accountId);
  const history = useRepaymentHistory(accountId, showAll);
  const query = showAll ? history : recent;
  // The full history's error counts only while it is shown: its query is off on the recent rows,
  // so a kept error could never be retried there.
  const accessError = [recent.error, showAll ? history.error : null].find(isAccessFailure);
  const rows = accessError
    ? undefined
    : showAll
      ? history.data?.pages.flatMap(({ content }) => content)
      : recent.data?.content;
  const error = accessError ?? query.error;

  if (!showAll && rows?.length === 0 && !error) return null;
  const canExpand = showAll || (recent.data?.page.totalPages ?? 0) > 1;
  return (
    <LiabilityPanel
      title={msg(intl, 'loan.repayments')}
      // With the full history open, the way back to the recent rows must survive an error.
      action={
        canExpand && (showAll || !accessError) ? (
          <LiabilityLink
            label={msg(intl, showAll ? 'loan.showRecent' : 'loan.seeAll')}
            expanded={showAll}
            onPress={() => {
              if (showAll && accessError) {
                // Access was denied: the cached recent rows and interest sum may no longer be
                // readable, so they load again instead of coming back from the cache.
                for (const queryKey of [
                  queryKeys.loanRepayments(accountId, 3),
                  queryKeys.loanYearlyTotals(accountId),
                ]) {
                  void queryClient.resetQueries({ queryKey, exact: true });
                }
              }
              setView({
                showAll: !showAll,
                expandedIds: showAll
                  ? expandedIds.filter((id) => recent.data?.content.some((row) => row.id === id))
                  : expandedIds,
              });
            }}
          />
        ) : null
      }
    >
      <InterestThisYear accountId={accountId} unavailable={!!accessError} />
      {error ? (
        <ErrorBanner
          error={error}
          onRetry={() => {
            // Retry the request that owns the error, also when the access error came from the
            // other list.
            if (recent.isError) void recent.refetch();
            if (showAll && history.isFetchNextPageError) void history.fetchNextPage();
            else if (showAll && history.isError) void history.refetch();
            else if (!recent.isError) void query.refetch();
          }}
        />
      ) : null}
      {query.isPending && query.isFetching ? <LoadingSkeleton count={2} /> : null}
      {query.isPending && query.isPaused ? (
        <InfoText>{msg(intl, 'loan.repaymentsOffline')}</InfoText>
      ) : null}
      <RefreshingIndicator active={query.isRefetching || (showAll && history.isFetchingNextPage)} />
      {showAll && rows?.length === 0 ? <InfoText>{msg(intl, 'loan.noRepayments')}</InfoText> : null}
      {rows?.map((repayment) => {
        const expanded = expandedIds.includes(repayment.id);
        return (
          <RepaymentRow
            key={repayment.id}
            repayment={repayment}
            onTransaction={
              onTransaction &&
              ((id, transactionAccountId) => {
                if (!inAside) saveForBack();
                onTransaction(id, transactionAccountId);
              })
            }
            expanded={expanded}
            onToggle={() =>
              setView({
                showAll,
                expandedIds: expanded
                  ? expandedIds.filter((id) => id !== repayment.id)
                  : [...expandedIds, repayment.id],
              })
            }
          />
        );
      })}
      {showAll && !accessError && history.hasNextPage && !history.isFetchNextPageError ? (
        <ActionButton
          label={msg(intl, 'account.loadMore')}
          disabled={!online || history.isFetching || history.isPaused}
          onPress={() => void history.fetchNextPage({ cancelRefetch: false })}
        />
      ) : null}
    </LiabilityPanel>
  );
}

/**
 * Field 7 (#712): the server's interest sum for the current calendar year, as the first block of
 * the Repayments panel. Hidden when nothing was paid this year; an unknown split is "Not known",
 * never 0, and the count stays (#722). When the sum cannot be loaded, or the loan's repayments are
 * not accessible, the amount is "–" and never a cached one (PO 2026-11-23). Payments without a
 * known split are left out of `interestPaid`, so their sent total is named below it
 * (PO 2026-11-24).
 */
function InterestThisYear({ accountId, unavailable }: { accountId: string; unavailable: boolean }) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const totals = useRepaymentYearlyTotals(accountId);
  const thisYear = Number(localDay(new Date()).slice(0, 4));
  const current = totals.data?.years.find((entry) => entry.year === thisYear);
  // A failed refresh keeps a loaded sum unless access was denied.
  const failed = unavailable || isAccessFailure(totals.error) || (totals.isError && !totals.data);
  if (!failed && (!current || !totals.data)) return null;

  // A string, so the year is not formatted as a grouped number.
  const year = String(thisYear);
  const title = msg(intl, 'loan.interestYtd', { year });
  const secondary = { ...TypeScale.caption, color: theme.textSecondary };
  if (failed || !current || !totals.data) {
    return (
      <View
        accessible
        role="group"
        accessibilityLabel={msg(intl, 'loan.interestYtdUnavailable', { year })}
      >
        <Text style={{ ...TypeScale.labelSmall, color: theme.textSecondary }}>{title}</Text>
        <Text style={{ ...TypeScale.numberLarge, color: theme.textPrimary }}>–</Text>
      </View>
    );
  }

  const { currency } = totals.data;
  const { interestPaid, paymentCount, splitEstimated, unknownSplitAmount } = current;
  const known = interestPaid != null;
  const interest = known
    ? amountTexts(intl, { value: interestPaid, currency, masked: hidden })
    : null;
  // The count stays when no split is known, so "Not known" does not read as nothing paid (#722).
  const meta = msg(intl, 'loan.interestYtdCount', { count: paymentCount });
  const unsplit =
    known && Number(unknownSplitAmount) > 0
      ? amountTexts(intl, { value: unknownSplitAmount, currency, masked: hidden })
      : null;
  const label = [
    title,
    interest?.label ?? msg(intl, 'loan.interestUnknown'),
    meta,
    // Same order as on screen: the badge sits in the count row, above the split-unknown line.
    splitEstimated ? msg(intl, 'loan.estimatedSplit') : null,
    known ? null : msg(intl, 'loan.splitUnknown'),
    // The children are not read on their own, so the visible explanation must be in the label.
    splitEstimated ? msg(intl, 'loan.interestYtdEstimated') : null,
    unsplit ? msg(intl, 'loan.interestYtdPartial', { amount: unsplit.label }) : null,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    // `role="group"`: on web an aria-label is only valid on an element with a role.
    <View accessible role="group" accessibilityLabel={label} style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelSmall, color: theme.textSecondary }}>{title}</Text>
      {known ? (
        <Amount value={interestPaid} currency={currency} style={TypeScale.numberLarge} />
      ) : (
        <Text style={{ ...TypeScale.numberLarge, color: theme.textSecondary }}>
          {msg(intl, 'loan.interestUnknown')}
        </Text>
      )}
      <View
        style={{
          flexDirection: 'row',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: Spacing.two,
        }}
      >
        <Text style={secondary}>{meta}</Text>
        {splitEstimated ? (
          // The wrapper takes the row's centering; the badge itself keeps `alignSelf`.
          <View>
            <LiabilityBadge tone="warning" label={msg(intl, 'loan.estimatedSplit')} />
          </View>
        ) : null}
      </View>
      {known ? null : <Text style={secondary}>{msg(intl, 'loan.splitUnknown')}</Text>}
      {splitEstimated ? (
        <Text style={secondary}>{msg(intl, 'loan.interestYtdEstimated')}</Text>
      ) : null}
      {unsplit ? (
        <Text style={secondary}>
          {intl.formatMessage(
            { id: 'loan.interestYtdPartial' },
            {
              amount: (
                <Amount
                  key="unsplit"
                  value={unknownSplitAmount}
                  currency={currency}
                  style={secondary}
                />
              ),
            },
          )}
        </Text>
      ) : null}
    </View>
  );
}

function RepaymentRow({
  repayment,
  onTransaction,
  expanded,
  onToggle,
}: {
  repayment: Repayment;
  onTransaction?: OpenRepaymentTransaction;
  expanded: boolean;
  onToggle: () => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const { currency, amount, bookingDate, principalAmount, interestAmount } = repayment;
  const date = formatDate(bookingDate, intl.locale);
  const principal = <Amount key="principal" value={principalAmount} currency={currency} />;
  const interest = <Amount key="interest" value={interestAmount} currency={currency} />;
  const split = intl.formatMessage(
    {
      id:
        principalAmount != null && interestAmount != null
          ? 'loan.split'
          : principalAmount != null
            ? 'loan.principalOnly'
            : interestAmount != null
              ? 'loan.interestOnly'
              : 'loan.splitUnknown',
    },
    { principal, interest },
  );
  const source = repayment.splitSource;
  const explanation =
    source === 'SCHEDULE'
      ? 'loan.splitSchedule'
      : source === 'ESTIMATED'
        ? 'loan.splitRate'
        : 'loan.splitOther';
  const transactions = Array.isArray(repayment.transactions)
    ? repayment.transactions.filter(
        (row) => typeof row?.id === 'string' && typeof row.accountId === 'string',
      )
    : [];

  return (
    <View
      style={{
        borderTopWidth: 1,
        borderColor: theme.borderDefault,
        paddingTop: Spacing.two,
        gap: Spacing.one,
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
        <InfoText>{date}</InfoText>
        <Amount value={amount} currency={currency} />
      </View>
      <InfoText>{split}</InfoText>
      {repayment.splitEstimated === true ? (
        <>
          <LiabilityBadge tone="warning" label={msg(intl, 'loan.estimatedSplit')} />
          <InfoText>{msg(intl, explanation)}</InfoText>
        </>
      ) : null}
      {transactions.length > 0 && onTransaction ? (
        <>
          <LiabilityLink
            label={msg(intl, 'loan.repaymentTransactions', { date })}
            expanded={expanded}
            onPress={onToggle}
          />
          {expanded
            ? transactions.map((transaction) => (
                <View
                  key={transaction.id}
                  style={{
                    flexDirection: 'row',
                    flexWrap: 'wrap',
                    alignItems: 'center',
                    gap: Spacing.two,
                  }}
                >
                  <LiabilityLink
                    label={transactionTypeLabel(transaction.transactionType, intl)}
                    onPress={() => onTransaction(transaction.id, transaction.accountId)}
                  />
                  <Amount value={transaction.amount} currency={transaction.currency} />
                </View>
              ))
            : null}
        </>
      ) : null}
    </View>
  );
}
