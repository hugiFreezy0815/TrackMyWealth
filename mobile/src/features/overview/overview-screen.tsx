import { useState, useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import {
  Platform,
  Pressable,
  RefreshControl,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';

import {
  useAccounts,
  useCashFlow,
  useNetWorth,
  usePortfolios,
  useWorkspace,
} from '@/api/queries/resources';
import { ErrorBanner, LoadingSkeleton, OfflineBanner } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { CashFlowCard } from './cash-flow-card';
import { CurrencyScope } from './currency-scope';
import { DataStatusBanner } from './data-status-banner';
import { ExplanationSheet } from './explanation-sheet';
import { FirstRun } from './first-run';
import { isFirstRunEmpty, monthKey, scopeCurrencies, unreconciledAccounts } from './overview-model';
import { InfoText, msg } from './overview-text';
import { PortfoliosCard } from './portfolios-card';
import { SectionTiles } from './section-tiles';
import { TabStrip } from './tab-strip';
import { AssetsLiabilitiesCard, NetWorthCard, type FinancialPart } from './totals-cards';

export type OverviewScreenProps = {
  onAddPortfolio: () => void;
  onReviewAccount: (accountId: string) => void;
  onPortfolio?: (portfolioId: string) => void;
};

/** S1 web layout: two columns from 1024 px, the right one 360 px wide. */
export const DESKTOP_BREAKPOINT = 1024;
/** Below this width the large figures use smaller type so they never break mid-number. */
export const COMPACT_BREAKPOINT = 600;
const RIGHT_COLUMN_WIDTH = 360;

const GREETINGS = {
  morning: 'overview.greeting.morning',
  afternoon: 'overview.greeting.afternoon',
  evening: 'overview.greeting.evening',
} as const;

function greetingId(hour: number): string {
  if (hour < 12) return GREETINGS.morning;
  return hour < 18 ? GREETINGS.afternoon : GREETINGS.evening;
}

function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}

/** US-01-15 / S1: independent TanStack queries, no financial arithmetic in the UI. */
export function OverviewScreen({
  onAddPortfolio,
  onReviewAccount,
  onPortfolio,
}: OverviewScreenProps) {
  const intl = useIntl();
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const desktop = width >= DESKTOP_BREAKPOINT;
  const compact = width < COMPACT_BREAKPOINT;
  const online = useOnline();
  const [selectedCurrency, selectCurrency] = useState<string | null>(null);
  const [explanation, setExplanation] = useState<FinancialPart | null>(null);
  const hidden = useAmountsHidden();
  // Recomputed on every render, so a session that crosses a month boundary asks for the new month.
  const month = monthKey(new Date());
  const workspace = useWorkspace();
  const portfolios = usePortfolios();
  // The scope currency defaults to the workspace currency: wait for it instead of firing a
  // currency-less request that would be refetched (under another key) a moment later.
  const currency = selectedCurrency ?? workspace.data?.currency;
  const netWorth = useNetWorth(currency, { enabled: currency !== undefined });
  const accounts = useAccounts();
  const cashFlow = useCashFlow(month, currency, { enabled: currency !== undefined });
  const results = [workspace, portfolios, netWorth, accounts, cashFlow];
  const refreshing = results.some((result) => result.isRefetching);
  const refresh = () => {
    void Promise.allSettled(results.map((result) => result.refetch()));
  };
  const portfolioItems = portfolios.data ?? [];
  const unreconciled = unreconciledAccounts(accounts.data ?? []);
  const firstRun = Boolean(
    portfolios.isSuccess &&
    netWorth.isSuccess &&
    netWorth.data &&
    isFirstRunEmpty(portfolioItems, netWorth.data),
  );
  const noAccounts =
    portfolioItems.length > 0 &&
    portfolioItems.every((portfolio) => portfolio.activeAccountCount === 0);
  // A disabled query stays pending; with the workspace failed, its own banner offers the retry.
  const waitingFor = (query: { isPending: boolean }) => query.isPending && !workspace.isError;

  const mainColumn = (
    <View style={{ gap: Spacing.three }}>
      {waitingFor(netWorth) ? <LoadingSkeleton count={4} height={28} /> : null}
      {netWorth.isError ? (
        <ErrorBanner error={netWorth.error} onRetry={() => void netWorth.refetch()} />
      ) : null}
      {netWorth.data ? (
        <>
          <NetWorthCard
            data={netWorth.data}
            hidden={hidden}
            explain={setExplanation}
            intl={intl}
            compact={compact}
          />
          {noAccounts ? <InfoText>{msg(intl, 'overview.noAccounts')}</InfoText> : null}
          <AssetsLiabilitiesCard
            data={netWorth.data}
            hidden={hidden}
            explain={setExplanation}
            intl={intl}
          />
          <SectionTiles data={netWorth.data} hidden={hidden} intl={intl} />
        </>
      ) : null}
    </View>
  );
  const sideColumn = (
    <View style={{ gap: Spacing.three }}>
      {portfolios.isPending ? <LoadingSkeleton count={3} /> : null}
      {portfolios.isError ? (
        <ErrorBanner error={portfolios.error} onRetry={() => void portfolios.refetch()} />
      ) : null}
      {portfolios.data ? (
        <PortfoliosCard
          items={portfolioItems}
          hidden={hidden}
          intl={intl}
          onPortfolio={onPortfolio}
        />
      ) : null}
      {waitingFor(cashFlow) ? <LoadingSkeleton count={3} /> : null}
      {cashFlow.isError ? (
        <ErrorBanner error={cashFlow.error} onRetry={() => void cashFlow.refetch()} />
      ) : null}
      {cashFlow.data && currency ? (
        <CashFlowCard data={cashFlow.data} intl={intl} currency={currency} hidden={hidden} />
      ) : null}
    </View>
  );

  return (
    <ScrollView
      testID="overview-screen"
      style={{ flex: 1, backgroundColor: theme.bgCanvas }}
      // The shell pads 16 px on a phone (M03 gutter) and 24 px above; on desktop the extra 16 px
      // make the W01 40 px content padding.
      contentContainerStyle={{
        paddingHorizontal: desktop ? Spacing.three : 0,
        paddingBottom: Spacing.three,
        gap: Spacing.three,
      }}
      refreshControl={
        Platform.OS === 'web' ? undefined : (
          <RefreshControl refreshing={refreshing} onRefresh={refresh} />
        )
      }
    >
      {/* The shell (#487) renders the page heading "Overview"; the screen adds the greeting. */}
      <InfoText>{msg(intl, greetingId(new Date().getHours()))}</InfoText>
      {Platform.OS === 'web' ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={msg(intl, 'overview.refresh')}
          onPress={refresh}
          style={{ minHeight: 44, alignSelf: 'flex-start', justifyContent: 'center' }}
        >
          <Text style={{ color: theme.brandLink }}>{msg(intl, 'overview.refresh')}</Text>
        </Pressable>
      ) : null}
      {!online ? (
        <OfflineBanner
          lastUpdated={
            netWorth.dataUpdatedAt ? new Date(netWorth.dataUpdatedAt).toISOString() : undefined
          }
        />
      ) : null}
      {workspace.isPending ? <LoadingSkeleton count={1} /> : null}
      {workspace.isError ? (
        <ErrorBanner error={workspace.error} onRetry={() => void workspace.refetch()} />
      ) : null}
      {workspace.data ? (
        <>
          <TabStrip
            name={workspace.data.name}
            intl={intl}
            offline={!online}
            onAddPortfolio={onAddPortfolio}
            portfolios={portfolioItems}
            onPortfolio={onPortfolio}
          />
          <CurrencyScope
            currency={currency ?? workspace.data.currency}
            choices={scopeCurrencies(workspace.data.currency)}
            choose={selectCurrency}
            intl={intl}
          />
        </>
      ) : null}
      {netWorth.data ? (
        <InfoText>
          {msg(intl, 'overview.asOf', { date: formatDate(netWorth.data.asOf, intl.locale) })}
        </InfoText>
      ) : null}
      {accounts.isError ? (
        <ErrorBanner error={accounts.error} onRetry={() => void accounts.refetch()} />
      ) : null}
      <DataStatusBanner accounts={unreconciled} intl={intl} onReviewAccount={onReviewAccount} />
      {firstRun ? (
        <FirstRun intl={intl} offline={!online} onAddPortfolio={onAddPortfolio} />
      ) : desktop ? (
        <View
          testID="overview-columns"
          style={{ flexDirection: 'row', alignItems: 'flex-start', gap: Spacing.three }}
        >
          <View style={{ flex: 1, minWidth: 0 }}>{mainColumn}</View>
          <View testID="overview-side-column" style={{ width: RIGHT_COLUMN_WIDTH }}>
            {sideColumn}
          </View>
        </View>
      ) : (
        <>
          {mainColumn}
          {sideColumn}
        </>
      )}
      <ExplanationSheet
        part={explanation}
        data={netWorth.data}
        hidden={hidden}
        close={() => setExplanation(null)}
        intl={intl}
      />
    </ScrollView>
  );
}
