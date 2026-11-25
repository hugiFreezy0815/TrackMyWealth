import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Platform, View } from 'react-native';

import { ActionButton, ErrorBanner, LoadingSkeleton, SectionCard } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { InfoText, msg } from '@/features/overview/overview-text';
import type { PositionSort } from '@/features/portfolio-view/model';
import { PositionView } from '@/features/portfolio-view/position-view';

import { orderPositions } from './model';
import type { useInvestments } from './use-investments';

const COLLAPSED_ROWS = 20;

export function DepotPositions({
  group,
  showDepotName,
  sort,
  onSort,
  onAccount,
}: {
  group: ReturnType<typeof useInvestments>['groups'][number];
  showDepotName: boolean;
  sort: PositionSort;
  onSort: (key: PositionSort['key']) => void;
  onAccount: () => void;
}) {
  const intl = useIntl();
  const [expanded, setExpanded] = useState(false);
  const { account, query } = group;
  const data = query.data;
  const sorted = data ? orderPositions(data, sort, intl.locale) : [];
  const collapsed = Platform.OS !== 'web' && !expanded && sorted.length > COLLAPSED_ROWS;
  const title = showDepotName ? account.name : msg(intl, 'investments.positions');

  return (
    <SectionCard title={title}>
      {query.isPending ? <LoadingSkeleton count={3} /> : null}
      {query.isError ? (
        <ErrorBanner error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {showDepotName && data?.complete === false ? (
        <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
      ) : null}
      {data && sorted.length > 0 ? (
        <View style={{ gap: Spacing.two }}>
          <PositionView
            positions={collapsed ? sorted.slice(0, COLLAPSED_ROWS) : sorted}
            currency={data.currency}
            accountName={account.name}
            investments={{ asOf: data.asOf, showDepotName, sort, onSort }}
          />
          {collapsed ? (
            <ActionButton
              label={msg(intl, 'portfolio.positions.all', { count: sorted.length })}
              onPress={() => setExpanded(true)}
            />
          ) : null}
        </View>
      ) : null}
      {data && sorted.length === 0 ? (
        <>
          <InfoText>{msg(intl, 'investments.noPositions')}</InfoText>
          <InfoText>{msg(intl, 'investments.holdingsUnavailable')}</InfoText>
          <ActionButton label={msg(intl, 'investments.openAccount')} onPress={onAccount} />
        </>
      ) : null}
    </SectionCard>
  );
}
