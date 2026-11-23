import { useIntl } from 'react-intl';
import { View } from 'react-native';

import { Amount, ErrorBanner, LoadingSkeleton, RefreshingIndicator } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { isAccessFailure, isNotFound } from '@/features/account-detail/access';
import type { AccountSummary } from '@/features/account-detail/model';
import { InfoText, msg } from '@/features/overview/overview-text';

import { LoanSchedulePanel } from './loan-schedule';
import { LiabilityBadge, LiabilityPanel } from './panel';
import { useCardStatement } from './queries';
import { RepaymentsPanel, type OpenRepaymentTransaction } from './repayments';

export function LiabilityDetail({
  account,
  onTransaction,
  inAside = false,
}: {
  account: AccountSummary;
  onTransaction?: OpenRepaymentTransaction;
  inAside?: boolean;
}) {
  if (!account.hasAmortisation && !account.hasStatementCycle) return null;
  return (
    <View style={{ gap: Spacing.three }}>
      {account.hasAmortisation ? (
        <>
          <LoanSchedulePanel accountId={account.id} />
          <RepaymentsPanel accountId={account.id} onTransaction={onTransaction} inAside={inAside} />
        </>
      ) : null}
      {account.hasStatementCycle ? <CardStatementPanel accountId={account.id} /> : null}
    </View>
  );
}

function CardStatementPanel({ accountId }: { accountId: string }) {
  const intl = useIntl();
  const query = useCardStatement(accountId);
  if (isNotFound(query.error)) return null;
  const statement = isAccessFailure(query.error) ? undefined : query.data;
  const date = (value: string) => formatDate(value, intl.locale);

  return (
    <LiabilityPanel title={msg(intl, 'card.statement.title')}>
      {query.isError ? (
        <ErrorBanner error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.isPending && query.isFetching ? <LoadingSkeleton count={2} /> : null}
      {query.isPending && query.isPaused ? (
        <InfoText>{msg(intl, 'card.statement.offline')}</InfoText>
      ) : null}
      <RefreshingIndicator active={query.isRefetching} />
      {statement === null ? <InfoText>{msg(intl, 'card.noStatement')}</InfoText> : null}
      {statement ? (
        <>
          <InfoText>
            {msg(intl, 'card.statement.period', {
              from: date(statement.periodStart),
              to: date(statement.periodEnd),
            })}
          </InfoText>
          {statement.closingBalance == null ? (
            <InfoText>{msg(intl, 'card.statement.amountUnknown')}</InfoText>
          ) : (
            <Amount value={statement.closingBalance} currency={statement.currency} />
          )}
          <InfoText>{msg(intl, 'card.statement.due', { date: date(statement.dueDate) })}</InfoText>
          <LiabilityBadge
            tone={statement.paid ? 'success' : 'warning'}
            label={msg(intl, statement.paid ? 'card.paid' : 'card.notPaid')}
          />
        </>
      ) : null}
    </LiabilityPanel>
  );
}
