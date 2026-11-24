import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Platform, useWindowDimensions, View } from 'react-native';

import { ActionButton, ErrorBanner, LoadingSkeleton, SectionCard } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { InfoText, msg } from '@/features/overview/overview-text';
import { PositionView } from '@/features/portfolio-view/position-view';

import { orderPositions, type PositionOrder } from './model';
import type { useInvestments } from './use-investments';

const COLLAPSED_ROWS = 20;

export function DepotPositions({
  group,
  showDepotName,
  order,
  onImport,
  online,
}: {
  group: ReturnType<typeof useInvestments>['groups'][number];
  showDepotName: boolean;
  order: PositionOrder;
  onImport: () => void;
  online: boolean;
}) {
  const intl = useIntl();
  const { width } = useWindowDimensions();
  const [expanded, setExpanded] = useState(false);
  const { account, query } = group;
  const data = query.data;
  const sorted = data ? orderPositions(data, order, intl.locale) : [];
  const collapsed =
    (Platform.OS !== 'web' || width < 600) && !expanded && sorted.length > COLLAPSED_ROWS;
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
            investments={{ asOf: data.asOf, showDepotName }}
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
          <ActionButton
            label={msg(intl, 'investments.import')}
            onPress={onImport}
            disabled={!online}
          />
        </>
      ) : null}
    </SectionCard>
  );
}
