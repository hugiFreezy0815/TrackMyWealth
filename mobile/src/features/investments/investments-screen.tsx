import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Platform, Pressable, RefreshControl, Text, useWindowDimensions } from 'react-native';

import { usePortfolioHistory } from '@/api/queries/resources';
import {
  ActionButton,
  Amount,
  BottomSheet,
  ChipGroup,
  ErrorBanner,
  LoadingSkeleton,
  OfflineBanner,
  RefreshingIndicator,
  SectionCard,
  ValueStatusBadge,
  isValueKnown,
} from '@/components/shared';
import { TypeScale } from '@/constants/theme';
import { InfoText, msg } from '@/features/overview/overview-text';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { ShellScreen } from '@/features/shell/screen';
import { HistoryPanel, historyForDisplay } from '@/features/value-history/history-panel';
import { useHistoryPeriod } from '@/features/value-history/period';
import { formatDate } from '@/format/date';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import type { PositionOrder } from './model';
import { PerformanceBreakdown, PerformanceCard } from './performance';
import { DepotPositions } from './positions';
import { useInvestments } from './use-investments';

const ALL_DEPOTS = 'all-depots';
const ORDERS = ['value', 'name', 'gain'] as const;

export function InvestmentsScreen({
  accountId,
  onScope,
  onAddDepot,
  onImport,
}: {
  accountId?: string;
  onScope: (accountId?: string) => void;
  onAddDepot: (portfolioId?: string) => void;
  onImport: (accountId?: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const hidden = useAmountsHidden();
  const { width } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1024;
  const [order, setOrder] = useState<PositionOrder>('value');
  const [performanceAccount, setPerformanceAccount] = useState<string>();
  const [showPortfolioPicker, setShowPortfolioPicker] = useState(false);
  const [explainedScope, setExplainedScope] = useState<string>();
  const period = useHistoryPeriod();
  const data = useInvestments(accountId);
  const selectedId = data.depot?.account.accountId;
  const scope = selectedId ?? (data.showAll ? ALL_DEPOTS : undefined);
  const historyId = data.depot?.historyPortfolioId;
  const history = usePortfolioHistory(historyId ?? '', period, Boolean(historyId));
  const displayedHistory = historyForDisplay(history, period, online);
  const results = [
    ...data.directoryQueries,
    ...data.valueQueries,
    ...data.positions,
    ...(historyId ? [history] : []),
  ];
  const refreshing = results.some(({ isRefetching }) => isRefetching);
  const refresh = () => void Promise.allSettled(results.map((result) => result.refetch()));
  const lastUpdated = Math.max(0, ...results.map(({ dataUpdatedAt }) => dataUpdatedAt));
  const { value, performance } = data.figures;
  const performanceOpen = selectedId != null && selectedId === performanceAccount;
  const closePerformance = () => setPerformanceAccount(undefined);
  const performanceTitle = msg(intl, 'investments.performance.title', {
    depot: data.depot?.account.name ?? '',
  });
  const breakdown =
    performance && value && performanceOpen ? (
      <PerformanceBreakdown data={performance} value={value} />
    ) : null;
  const performanceCard =
    performance && value ? (
      <PerformanceCard
        data={performance}
        currency={value.currency}
        onOpen={() => setPerformanceAccount(selectedId)}
      />
    ) : null;
  const noDepots = data.directoryComplete && data.depots.length === 0;
  const addDepot = () => {
    if (data.portfolios && data.portfolios.length > 1) setShowPortfolioPicker(true);
    else onAddDepot(data.portfolios?.[0]?.id);
  };

  return (
    <ShellScreen
      title={msg(intl, 'nav.investments')}
      refreshControl={
        Platform.OS === 'web' ? undefined : (
          <RefreshControl refreshing={refreshing} onRefresh={refresh} />
        )
      }
      aside={
        desktop ? (
          breakdown ? (
            <SectionCard title={performanceTitle}>
              <ActionButton label={msg(intl, 'ui.close')} onPress={closePerformance} />
              {breakdown}
            </SectionCard>
          ) : (
            performanceCard
          )
        ) : undefined
      }
    >
      {!online ? (
        <OfflineBanner
          lastUpdated={lastUpdated ? new Date(lastUpdated).toISOString() : undefined}
        />
      ) : null}
      {Platform.OS === 'web' && online ? (
        <Pressable
          accessibilityRole="button"
          onPress={refresh}
          style={{ alignSelf: 'flex-start', minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>
            {msg(intl, 'overview.refresh')}
          </Text>
        </Pressable>
      ) : null}
      <RefreshingIndicator active={refreshing} />
      {data.directoryPending ? <LoadingSkeleton count={2} /> : null}
      {[...data.directoryQueries, ...data.valueQueries].map((result, index) =>
        result.isError ? (
          <ErrorBanner key={index} error={result.error} onRetry={() => void result.refetch()} />
        ) : null,
      )}
      {data.invalidScope ? (
        <InfoText warning>{msg(intl, 'investments.depotMissing')}</InfoText>
      ) : null}
      {noDepots ? (
        <SectionCard title={msg(intl, 'investments.empty')}>
          {!showPortfolioPicker ? (
            <ActionButton
              label={msg(intl, 'investments.add')}
              onPress={addDepot}
              disabled={!online}
            />
          ) : (
            <ChipGroup
              label={msg(intl, 'overview.portfolios')}
              options={data.portfolios?.map(({ id, name }) => ({ value: id, label: name })) ?? []}
              selected={[]}
              onSelect={onAddDepot}
              disabled={!online}
            />
          )}
          <ActionButton
            label={msg(intl, 'investments.import')}
            onPress={() => onImport()}
            disabled={!online}
          />
        </SectionCard>
      ) : null}
      {data.depots.length > 0 ? (
        <ChipGroup
          label={msg(intl, 'investments.scope')}
          options={[
            { value: ALL_DEPOTS, label: msg(intl, 'investments.all') },
            ...data.depots.map(({ account }) => ({
              value: account.accountId,
              label: account.name,
            })),
          ]}
          selected={scope ? [scope] : []}
          onSelect={(id) => {
            closePerformance();
            onScope(id === ALL_DEPOTS ? undefined : id);
          }}
        />
      ) : null}
      {data.showAll &&
      data.depots.length > 0 &&
      data.valueQueries.some(({ isPending }) => isPending) ? (
        <LoadingSkeleton count={2} />
      ) : null}
      {value && !noDepots ? (
        <SectionCard title={msg(intl, 'investments.performance.marketValue')}>
          <Amount
            value={value.amount}
            currency={value.currency}
            valueKnown={value.known && isValueKnown(value.status)}
            style={TypeScale.headingLarge}
          />
          <ValueStatusBadge
            status={value.status}
            valueSourceDate={data.showAll ? undefined : value.asOf}
          />
          {value.asOf ? (
            <InfoText>
              {msg(intl, 'overview.explain.asOf', { date: formatDate(value.asOf, intl.locale) })}
            </InfoText>
          ) : null}
          {!value.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
          <Pressable
            accessibilityRole="button"
            aria-expanded={scope === explainedScope}
            onPress={() => setExplainedScope(scope === explainedScope ? undefined : scope)}
            style={{ alignSelf: 'flex-start', minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ color: theme.brandLink }}>
              {msg(intl, 'investments.explain', {
                label: msg(intl, 'investments.performance.marketValue'),
              })}
            </Text>
          </Pressable>
          {scope === explainedScope ? (
            <InfoText>
              {msg(
                intl,
                data.showAll
                  ? 'investments.explanation.depotSubtotal'
                  : 'investments.explanation.marketValue',
              )}
            </InfoText>
          ) : null}
          {historyId ? (
            <HistoryPanel
              data={displayedHistory}
              query={history}
              online={online}
              period={period}
              hidden={hidden}
            />
          ) : null}
        </SectionCard>
      ) : null}
      {!desktop ? performanceCard : null}
      {data.groups.some(({ query }) => query.data?.positions.length) ? (
        <ChipGroup
          label={msg(intl, 'investments.sort')}
          options={ORDERS.map((value) => ({
            value,
            label: msg(intl, 'investments.sort.' + value),
          }))}
          selected={[order]}
          onSelect={(next) => {
            const option = ORDERS.find((value) => value === next);
            if (option) setOrder(option);
          }}
        />
      ) : null}
      {data.groups.map((group) => (
        <DepotPositions
          key={group.account.accountId}
          group={group}
          showDepotName={data.showAll}
          order={order}
          onImport={() => onImport(group.account.accountId)}
          online={online}
        />
      ))}
      {!desktop && breakdown ? (
        <BottomSheet
          title={performanceTitle}
          visible
          scrollable
          contentStyle={{ height: '100%' }}
          onClose={closePerformance}
        >
          {breakdown}
        </BottomSheet>
      ) : null}
    </ShellScreen>
  );
}
