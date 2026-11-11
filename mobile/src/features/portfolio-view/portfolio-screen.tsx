import { useEffect, useRef, useState } from 'react';
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

import { ApiError } from '@/api/client';
import { usePortfolioHistory, usePortfolioView, usePortfolios } from '@/api/queries/resources';
import {
  ActionButton,
  Amount,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  OfflineBanner,
  SectionCard,
  ValueStatusBadge,
  amountTexts,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { isNegativeAmount } from '@/features/overview/overview-model';
import { msg } from '@/features/overview/overview-text';
import { TabStrip } from '@/features/overview/tab-strip';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import { PortfolioAccountRow } from './account-row';
import { accountRows, sectionMessageKey, type PortfolioSection } from './model';
import { DataSourceCard, PortfolioDetails } from './portfolio-details';
import { PortfolioExplanation, type Explained } from './portfolio-explanation';
import { HistoryPanel, historyForDisplay } from '@/features/value-history/history-panel';
import { useHistoryPeriod } from '@/features/value-history/period';
import { PortfolioHeader, headerData } from './portfolio-header';

/** S2 web layout: two columns from 1024 px (README breakpoints), the right one 320 px wide. */
const DESKTOP_BREAKPOINT = 1024;

type Props = {
  portfolioId: string;
  overviewName: string | null;
  onOverview: () => void;
  onPortfolio: (id: string) => void;
  onAddPortfolio: () => void;
  onAddAccount: (portfolioId: string) => void;
  onImport: () => void;
  onAccount: (accountId: string) => void;
  onMissing: () => void;
};

function SectionCardView({
  section,
  currency,
  onExplain,
  onAccount,
}: {
  section: PortfolioSection;
  currency: string;
  onExplain: () => void;
  onAccount: (accountId: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const title = msg(intl, sectionMessageKey(section.section));
  const known = section.valueStatus !== 'UNKNOWN';
  return (
    <SectionCard title={title}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={msg(intl, 'portfolio.section.explain', {
          section: title,
          amount: amountTexts(intl, {
            value: section.subtotal,
            currency,
            valueKnown: known,
            masked: hidden,
          }).label,
        })}
        onPress={onExplain}
        style={{ minHeight: 44, justifyContent: 'center', gap: Spacing.one }}
      >
        <Amount
          value={section.subtotal}
          currency={currency}
          valueKnown={known}
          style={{
            ...TypeScale.headingMedium,
            color:
              !hidden && isNegativeAmount(section.subtotal) ? theme.statusLoss : theme.textPrimary,
          }}
        />
      </Pressable>
      <ValueStatusBadge status={section.valueStatus} />
      {!section.complete ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
          {msg(intl, 'total.incomplete')}
        </Text>
      ) : null}
      {accountRows(section).map(({ account, nested }) => (
        <PortfolioAccountRow
          key={account.accountId}
          account={account}
          section={section}
          nested={nested}
          portfolioCurrency={currency}
          onAccount={onAccount}
        />
      ))}
    </SectionCard>
  );
}

/** S2: server-ordered sections. Header survives a failed view query via the cached S1 item. */
export function PortfolioScreen({
  portfolioId,
  overviewName,
  onOverview,
  onPortfolio,
  onAddPortfolio,
  onAddAccount,
  onImport,
  onAccount,
  onMissing,
}: Props) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const { width } = useWindowDimensions();
  const desktop = width >= DESKTOP_BREAKPOINT;
  const portfolios = usePortfolios();
  const viewQuery = usePortfolioView(portfolioId);
  const view = viewQuery.data;
  const fallback = portfolios.data?.find((item) => item.id === portfolioId);
  const data = headerData(view, fallback, msg(intl, 'portfolio.unknownName'));
  const [explained, setExplained] = useState<Explained | null>(null);
  const period = useHistoryPeriod();
  const hidden = useAmountsHidden();
  const history = usePortfolioHistory(portfolioId, period, !!view);
  const results = [viewQuery, portfolios, ...(view ? [history] : [])];
  const refreshing = results.some((result) => result.isRefetching);
  const refresh = () => {
    void Promise.allSettled(results.map((result) => result.refetch()));
  };

  // Don't redirect while data is loading/offline. 404 alone means the portfolio was removed.
  const missing = viewQuery.error instanceof ApiError && viewQuery.error.status === 404;
  const missingHandled = useRef(false);
  useEffect(() => {
    if (missing && !missingHandled.current) {
      missingHandled.current = true;
      onMissing();
    }
  }, [missing, onMissing]);

  const dataSource =
    view && !data.personalAssetsDefault ? (
      <DataSourceCard view={view} online={online} onImport={onImport} />
    ) : null;
  const main = (
    <View style={{ gap: Spacing.three, flex: 1, minWidth: 0 }}>
      <PortfolioHeader data={data} view={view} onExplain={() => setExplained('total')}>
        {view ? (
          <HistoryPanel
            query={history}
            data={historyForDisplay(history, period, online)}
            online={online}
            period={period}
            hidden={hidden}
          />
        ) : null}
      </PortfolioHeader>
      {view?.sections.length === 0 ? (
        <EmptyState
          title={msg(intl, 'portfolio.empty.title', { name: data.name })}
          description={msg(intl, 'portfolio.empty.body')}
          actionLabel={msg(intl, 'portfolio.addAccount')}
          onAction={() => onAddAccount(portfolioId)}
        />
      ) : null}
      {view?.sections.map((section) => (
        <SectionCardView
          // One card per section code: the server sends each section at most once.
          key={section.section}
          section={section}
          currency={data.currency}
          onExplain={() => setExplained(section)}
          onAccount={onAccount}
        />
      ))}
      {desktop ? null : dataSource}
    </View>
  );

  return (
    <ScrollView
      testID="portfolio-screen"
      style={{ flex: 1 }}
      refreshControl={
        Platform.OS === 'web' ? undefined : (
          <RefreshControl refreshing={refreshing} onRefresh={refresh} />
        )
      }
      contentContainerStyle={{ paddingBottom: Spacing.four, gap: Spacing.three }}
    >
      <TabStrip
        name={overviewName}
        intl={intl}
        offline={!online}
        selectedId={portfolioId}
        portfolios={portfolios.data ?? []}
        onOverview={onOverview}
        onPortfolio={onPortfolio}
        onAddPortfolio={onAddPortfolio}
      />
      {Platform.OS === 'web' ? (
        <ActionButton label={msg(intl, 'portfolio.refresh')} onPress={refresh} />
      ) : null}
      {!online ? (
        <OfflineBanner
          lastUpdated={
            viewQuery.dataUpdatedAt ? new Date(viewQuery.dataUpdatedAt).toISOString() : undefined
          }
        />
      ) : null}
      {viewQuery.isPending && !fallback ? <LoadingSkeleton count={5} /> : null}
      {viewQuery.isRefetching ? (
        <Text style={{ color: theme.textSecondary }}>{msg(intl, 'portfolio.refreshing')}</Text>
      ) : null}
      {missing ? (
        <View style={{ gap: Spacing.two }}>
          <ErrorBanner message={msg(intl, 'portfolio.missing')} />
          <ActionButton label={msg(intl, 'portfolio.back')} onPress={onMissing} />
        </View>
      ) : viewQuery.isError ? (
        <ErrorBanner error={viewQuery.error} onRetry={() => void viewQuery.refetch()} />
      ) : null}
      {!missing && (view || fallback) ? (
        desktop ? (
          <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: Spacing.four }}>
            {main}
            {view ? (
              <View testID="portfolio-side-column" style={{ width: 320, gap: Spacing.three }}>
                <PortfolioDetails data={data} view={view} />
                {dataSource}
              </View>
            ) : null}
          </View>
        ) : (
          main
        )
      ) : null}
      <PortfolioExplanation
        explained={explained}
        data={data}
        view={view}
        onClose={() => setExplained(null)}
      />
    </ScrollView>
  );
}
