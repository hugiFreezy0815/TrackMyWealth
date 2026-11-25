import { useState } from 'react';
import { useIntl, type IntlShape } from 'react-intl';
import { Platform, Pressable, RefreshControl, Text, useWindowDimensions } from 'react-native';

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
import { ShellScreen } from '@/features/shell/screen';
import { formatDate } from '@/format/date';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import {
  POSITION_TABLE_MIN_WIDTH,
  sectionMessageKey,
  type PositionSort,
} from '@/features/portfolio-view/model';

import { InvestmentActivity } from './activity';
import { CHIP_SORT_KEYS, DEFAULT_POSITION_SORT, visiblePositionSort, type Depot } from './model';
import { PerformanceBreakdown, PerformanceCard } from './performance';
import { DepotPositions } from './positions';
import { useInvestments } from './use-investments';

const ALL_DEPOTS = 'all-depots';

export function InvestmentsScreen({
  accountId,
  currency,
  onScope,
  onAddDepot,
  onAccount,
}: {
  accountId?: string;
  /** The Overview scope currency the Depot tile showed; the subtotal uses it (default: workspace). */
  currency?: string;
  onScope: (accountId?: string) => void;
  onAddDepot: (portfolioId?: string) => void;
  onAccount: (accountId: string, tab: 'activity' | 'positions') => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const { width } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1024;
  const webTable = Platform.OS === 'web' && width >= POSITION_TABLE_MIN_WIDTH;
  const [chosenSort, setSort] = useState<PositionSort>(DEFAULT_POSITION_SORT);
  const sort = visiblePositionSort(chosenSort, webTable);
  const selectSort = (key: PositionSort['key']) =>
    setSort({
      key,
      direction:
        sort.key === key
          ? sort.direction === 'ascending'
            ? 'descending'
            : 'ascending'
          : key === 'name'
            ? 'ascending'
            : 'descending',
    });
  const [performanceAccount, setPerformanceAccount] = useState<string>();
  const [showPortfolioPicker, setShowPortfolioPicker] = useState(false);
  const [explainedScope, setExplainedScope] = useState<string>();
  const data = useInvestments(accountId, currency);
  const selectedId = data.depot?.account.accountId;
  const scope = selectedId ?? (data.showAll ? ALL_DEPOTS : undefined);
  const results = [
    ...data.directoryQueries,
    ...data.valueQueries,
    ...data.positions,
    ...data.accountQueries,
    ...(data.activity ? [data.activity] : []),
  ];
  const refreshing = results.some(({ isRefetching }) => isRefetching);
  const refresh = () => void Promise.allSettled(results.map((result) => result.refetch()));
  // Offline, the banner dates the oldest figure shown: a newer refetch of another query (the
  // workspace, a portfolio view) must not make a day-old value or position list look fresh.
  const loadedAt = data.figureQueries
    .map(({ dataUpdatedAt }) => dataUpdatedAt)
    .filter((at) => at > 0);
  const lastUpdated = loadedAt.length > 0 ? Math.min(...loadedAt) : 0;
  const { value, performance } = data.figures;
  const marketValueTitle = msg(
    intl,
    data.showAll ? 'investments.depotSubtotal' : 'investments.performance.marketValue',
  );
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
  // The subtotal leaves out Pillar 3a: say so only when such an account is in the list below.
  const listsPillar3a = data.depots.some(({ section }) => section === 'PILLAR_3A');
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
            {msg(intl, 'investments.refresh')}
          </Text>
        </Pressable>
      ) : null}
      <RefreshingIndicator active={refreshing} />
      {data.directoryPending ? <LoadingSkeleton count={2} /> : null}
      {[...data.directoryQueries, ...data.valueQueries, ...data.accountQueries].map(
        (result, index) =>
          result.isError ? (
            <ErrorBanner key={index} error={result.error} onRetry={() => void result.refetch()} />
          ) : null,
      )}
      {data.showAll && data.directoryFailed ? (
        <InfoText warning>{msg(intl, 'investments.depotsIncomplete')}</InfoText>
      ) : null}
      {data.invalidScope ? (
        <InfoText warning>{msg(intl, 'investments.depotMissing')}</InfoText>
      ) : null}
      {data.scopeUnavailable ? (
        <InfoText warning>{msg(intl, 'investments.depotNotLoaded')}</InfoText>
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
            <>
              <ChipGroup
                label={msg(intl, 'overview.portfolios')}
                options={data.portfolios?.map(({ id, name }) => ({ value: id, label: name })) ?? []}
                selected={[]}
                onSelect={onAddDepot}
                disabled={!online}
              />
              <ActionButton
                label={msg(intl, 'investments.addCancel')}
                onPress={() => setShowPortfolioPicker(false)}
              />
            </>
          )}
          <InfoText>{msg(intl, 'investments.holdingsUnavailable')}</InfoText>
        </SectionCard>
      ) : null}
      {data.depots.length > 0 ? (
        <ChipGroup
          label={msg(intl, 'investments.scope')}
          options={[
            { value: ALL_DEPOTS, label: msg(intl, 'investments.all') },
            ...data.depots.map((depot) => ({
              value: depot.account.accountId,
              label: depotLabel(intl, depot, data.depots),
            })),
          ]}
          selected={scope ? [scope] : []}
          onSelect={(id) => {
            closePerformance();
            onScope(id === ALL_DEPOTS ? undefined : id);
          }}
        />
      ) : null}
      {(data.showAll &&
        data.depots.length > 0 &&
        data.valueQueries.some(({ isPending }) => isPending)) ||
      value?.pending ? (
        <LoadingSkeleton count={2} />
      ) : null}
      {value && !value.pending && !noDepots ? (
        <SectionCard title={marketValueTitle}>
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
          {value.statusUnavailable ? (
            <InfoText>{msg(intl, 'investments.valueStatusUnavailable')}</InfoText>
          ) : null}
          {data.showAll ? (
            <InfoText>
              {msg(
                intl,
                listsPillar3a
                  ? 'investments.depotSubtotalScopePillar3a'
                  : 'investments.depotSubtotalScope',
              )}
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
                label: marketValueTitle,
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
                { currency: value.currency },
              )}
            </InfoText>
          ) : null}
        </SectionCard>
      ) : null}
      {data.showAll &&
      !value &&
      data.depots.length > 0 &&
      !data.directoryPending &&
      !data.directoryFailed &&
      data.valueQueries.every(({ isSuccess }) => isSuccess) ? (
        <InfoText>
          {msg(
            intl,
            listsPillar3a
              ? 'investments.depotSubtotalAbsentPillar3a'
              : 'investments.depotSubtotalAbsent',
          )}
        </InfoText>
      ) : null}
      {!desktop ? performanceCard : null}
      {!webTable && data.groups.some(({ query }) => query.data?.positions.length) ? (
        <ChipGroup
          label={msg(intl, 'investments.sort')}
          options={CHIP_SORT_KEYS.map((value) => {
            const label = msg(intl, 'investments.sort.' + value);
            if (value !== sort.key) return { value, label };
            // Tapping the selected chip again reverses the order, as the web table headers do.
            return {
              value,
              label: `${label} ${sort.direction === 'ascending' ? '↑' : '↓'}`,
              accessibilityLabel: msg(intl, 'investments.sort.' + sort.direction, { label }),
            };
          })}
          selected={[sort.key]}
          onSelect={(next) => {
            const option = CHIP_SORT_KEYS.find((value) => value === next);
            if (option) selectSort(option);
          }}
        />
      ) : null}
      {data.groups.map((group) => (
        <DepotPositions
          key={group.account.accountId}
          group={group}
          showDepotName={data.showAll}
          sort={sort}
          onSort={selectSort}
          onAccount={() => onAccount(group.account.accountId, 'positions')}
        />
      ))}
      {selectedId && data.activity ? (
        <InvestmentActivity
          activity={data.activity}
          onOpen={() => onAccount(selectedId, 'activity')}
        />
      ) : null}
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

/**
 * A depot named like another one (in another portfolio) also names its portfolio. Namesakes in
 * the same portfolio add their section, else their masked identifier, else their position.
 */
function depotLabel(intl: IntlShape, depot: Depot, depots: readonly Depot[]) {
  const namesakes = depots.filter(({ account }) => account.name === depot.account.name);
  if (namesakes.length <= 1) return depot.account.name;
  const twins = namesakes.filter(({ portfolioName }) => portfolioName === depot.portfolioName);
  if (twins.length <= 1)
    return msg(intl, 'investments.scopeInPortfolio', {
      depot: depot.account.name,
      portfolio: depot.portfolioName,
    });
  return msg(intl, 'investments.scopeInPortfolioDetail', {
    depot: depot.account.name,
    portfolio: depot.portfolioName,
    detail: twinDetail(intl, depot, twins),
  });
}

function twinDetail(intl: IntlShape, depot: Depot, twins: readonly Depot[]) {
  if (twins.filter(({ section }) => section === depot.section).length === 1)
    return msg(intl, sectionMessageKey(depot.section));
  const masked = depot.account.maskedIdentifier;
  if (masked && twins.filter(({ account }) => account.maskedIdentifier === masked).length === 1)
    return masked;
  return String(twins.indexOf(depot) + 1);
}
