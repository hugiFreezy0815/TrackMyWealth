import { useState } from 'react';
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
import { formatDate } from '@/format/date';
import { isAccessFailure } from '@/features/account-detail/access';
import { transactionTypeLabel } from '@/features/account-detail/model';
import { InfoText, msg } from '@/features/overview/overview-text';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import { LiabilityBadge, LiabilityLink, LiabilityPanel } from './panel';
import { type Repayment, useRecentRepayments, useRepaymentHistory } from './queries';

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
  const [showAll, setShowAll] = useState(false);
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
      {canExpand && !accessError ? (
        <LiabilityLink
          label={msg(intl, showAll ? 'loan.showRecent' : 'loan.seeAll')}
          expanded={showAll}
          onPress={() => setShowAll(!showAll)}
        />
      ) : null}
      {error ? (
        <ErrorBanner
          error={error}
          onRetry={() => {
            if (showAll && history.isFetchNextPageError) void history.fetchNextPage();
            else void query.refetch();
          }}
        />
      ) : null}
      {query.isPending && query.isFetching ? <LoadingSkeleton count={2} /> : null}
      {query.isPending && query.isPaused ? (
        <InfoText>{msg(intl, 'loan.repaymentsOffline')}</InfoText>
      ) : null}
      <RefreshingIndicator active={query.isRefetching || (showAll && history.isFetchingNextPage)} />
      {showAll && rows?.length === 0 ? <InfoText>{msg(intl, 'loan.noRepayments')}</InfoText> : null}
      {rows?.map((repayment) => (
        <RepaymentRow key={repayment.id} repayment={repayment} onTransaction={onTransaction} />
      ))}
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

function RepaymentRow({
  repayment,
  onTransaction,
}: {
  repayment: Repayment;
  onTransaction?: OpenRepaymentTransaction;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
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
            onPress={() => setExpanded(!expanded)}
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
