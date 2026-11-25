import { useIntl } from 'react-intl';
import { View } from 'react-native';

import {
  ActionButton,
  Amount,
  ErrorBanner,
  LoadingSkeleton,
  SectionCard,
} from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { transactionHeading } from '@/features/account-detail/model';
import { InfoText, msg } from '@/features/overview/overview-text';
import { formatDate } from '@/format/date';

import type { useInvestments } from './use-investments';

export function InvestmentActivity({
  activity,
  onOpen,
}: {
  activity: NonNullable<ReturnType<typeof useInvestments>['activity']>;
  onOpen: () => void;
}) {
  const intl = useIntl();
  const rows = activity.data?.content;
  return (
    <SectionCard title={msg(intl, 'investments.activity')}>
      {activity.isPending ? <LoadingSkeleton count={2} /> : null}
      {activity.isError ? (
        <ErrorBanner error={activity.error} onRetry={() => void activity.refetch()} />
      ) : null}
      {rows?.length === 0 ? <InfoText>{msg(intl, 'investments.activity.empty')}</InfoText> : null}
      {rows?.map((row) => (
        <View
          key={row.id}
          style={{
            flexDirection: 'row',
            flexWrap: 'wrap',
            justifyContent: 'space-between',
            gap: Spacing.two,
          }}
        >
          <View>
            <InfoText>{transactionHeading(row, intl)}</InfoText>
            <InfoText>{formatDate(row.bookingDate, intl.locale)}</InfoText>
          </View>
          <Amount value={row.amount} currency={row.currency} signRole="change" />
        </View>
      ))}
      <ActionButton label={msg(intl, 'investments.activity.open')} onPress={onOpen} />
    </SectionCard>
  );
}
