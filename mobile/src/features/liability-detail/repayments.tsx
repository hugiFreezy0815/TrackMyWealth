import { useIntl } from 'react-intl';
import { View } from 'react-native';

import {
  ActionButton,
  Amount,
  ErrorBanner,
  LoadingSkeleton,
  RefreshingIndicator,
} from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { formatDate, localDay } from '@/format/date';
import { isAccessFailure } from '@/features/account-detail/access';
import { transactionTypeLabel } from '@/features/account-detail/model';
import { InfoText, msg } from '@/features/overview/overview-text';
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
}: {
  accountId: string;
  onTransaction?: OpenRepaymentTransaction;
}) {
  const intl = useIntl();
  const online = useOnline();
  const { view, setView, saveForBack } = useRepaymentHistoryView(accountId);
  const { showAll, expandedIds } = view;
  const recent = useRecentRepayments(accountId);
  const history = useRepaymentHistory(accountId, showAll);
  const query = showAll ? history : recent;
  const accessError = [recent.error, history.error].find(isAccessFailure);
  const rows = accessError
    ? undefined
    : showAll
      ? history.data?.pages.flatMap(({ content }) => content)
      : recent.data?.content;
  const error = accessError ?? query.error;

  if (!showAll && rows?.length === 0 && !error) return null;
  const canExpand = showAll || (recent.data?.page.totalPages ?? 0) > 1;
  return (
    <LiabilityPanel title={msg(intl, 'loan.repayments')}>
      {accessError ? null : <InterestThisYear accountId={accountId} />}
      {/* With the full history open, the way back to the recent rows must survive an error. */}
      {canExpand && (showAll || !accessError) ? (
        <LiabilityLink
          label={msg(intl, showAll ? 'loan.showRecent' : 'loan.seeAll')}
          expanded={showAll}
          onPress={() =>
            setView({
              showAll: !showAll,
              expandedIds: showAll
                ? expandedIds.filter((id) => recent.data?.content.some((row) => row.id === id))
                : expandedIds,
            })
          }
        />
      ) : null}
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
                saveForBack();
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
 * Field 7: the server's interest sum for the current calendar year. Hidden when nothing was paid
 * this year or the sum cannot be loaded; an unknown split is "Not known", never 0.
 */
function InterestThisYear({ accountId }: { accountId: string }) {
  const intl = useIntl();
  const totals = useRepaymentYearlyTotals(accountId);
  if (isAccessFailure(totals.error)) return null;
  const year = Number(localDay(new Date()).slice(0, 4));
  const current = totals.data?.years.find((entry) => entry.year === year);
  if (!current || !totals.data) return null;
  return (
    <>
      <InfoText>
        {intl.formatMessage(
          { id: 'loan.interestYtd' },
          {
            amount:
              current.interestPaid == null ? (
                msg(intl, 'loan.interestUnknown')
              ) : (
                <Amount
                  key="interest"
                  value={current.interestPaid}
                  currency={totals.data.currency}
                />
              ),
          },
        )}
      </InfoText>
      {current.splitEstimated ? (
        <LiabilityBadge tone="warning" label={msg(intl, 'loan.estimatedSplit')} />
      ) : null}
    </>
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
