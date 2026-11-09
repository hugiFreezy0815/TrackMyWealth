import { useMemo, useState, useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { useIntl, type IntlShape } from 'react-intl';
import {
  Platform, Pressable, RefreshControl, ScrollView, Text, View, useWindowDimensions,
} from 'react-native';

import {
  useAccounts, useCashFlow, useNetWorth, usePortfolios, useWorkspace,
} from '@/api/queries/resources';
import {
  ActionButton, Amount, ChipGroup, Dialog, ErrorBanner, InstitutionLogo,
  LoadingSkeleton, OfflineBanner, SectionCard, ValueStatusBadge,
} from '@/components/shared';
import { amountTexts } from '@/components/shared/amount';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import {
  currencyAmount, hasNonZeroAmount, isFirstRunEmpty, largestLiabilities,
  monthKey, scopeCurrencies, unknownValuations, unreconciledAccounts,
  type CashFlow, type NetWorth, type Portfolio,
} from './overview-model';

export type OverviewScreenProps = {
  onAddPortfolio: () => void;
  onReviewAccount?: (accountId: string) => void;
};
type Explanation = 'netWorth' | 'assets' | 'liabilities' | null;
type FinancialPart = Exclude<Explanation, null>;

const sectionLabels: Record<NetWorth['sections'][number]['section'], string> = {
  CASH_AND_SAVINGS: 'overview.sections.CASH_AND_SAVINGS',
  PILLAR_3A: 'overview.sections.PILLAR_3A',
  PENSION: 'overview.sections.PENSION',
  DEPOT: 'overview.sections.DEPOT',
  CRYPTO: 'overview.sections.CRYPTO',
  CARDS: 'overview.sections.CARDS',
  PROPERTY_AND_MORTGAGE: 'overview.sections.PROPERTY_AND_MORTGAGE',
  OTHER: 'overview.sections.OTHER',
};
function msg(intl: IntlShape, id: string, values?: Record<string, string | number>): string {
  return intl.formatMessage({ id }, values);
}
function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}
function InfoText({ children, warning = false }: { children: React.ReactNode; warning?: boolean }) {
  const theme = useTheme();
  return (
    <Text style={{
      ...TypeScale.bodyMedium,
      color: warning ? theme.statusWarning : theme.textSecondary,
      flexShrink: 1,
    }}>{children}</Text>
  );
}

function TabStrip({
  name, intl, offline, onAddPortfolio,
}: { name: string | null; intl: IntlShape; offline: boolean; onAddPortfolio: () => void }) {
  const theme = useTheme();
  const label = name || msg(intl, 'overview.tab.allPortfolios');
  return (
    <View accessibilityRole="tablist" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
      <View
        accessibilityRole="tab"
        accessibilityState={{ selected: true }}
        accessibilityLabel={label}
        style={{
          minHeight: 44, maxWidth: 256, paddingHorizontal: 16, justifyContent: 'center',
          borderRadius: Radii.pill, backgroundColor: theme.bgInverse,
        }}
      >
        <Text numberOfLines={label.length > 24 ? 1 : undefined}
          style={{ ...TypeScale.labelMedium, color: theme.bgSurface }}>{label}</Text>
      </View>
      <Pressable
        accessibilityRole="button" accessibilityState={{ disabled: offline }}
        disabled={offline} onPress={onAddPortfolio}
        style={{
          minHeight: 44, justifyContent: 'center', paddingHorizontal: 16,
          borderRadius: Radii.pill, borderWidth: 1, borderColor: theme.borderInteractive,
          backgroundColor: theme.bgSurface, opacity: offline ? 0.5 : 1,
        }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {msg(intl, 'overview.tab.addPortfolio')}
        </Text>
      </Pressable>
    </View>
  );
}

function CurrencyScope({
  currency, choices, choose, intl,
}: { currency: string; choices: string[]; choose: (currency: string) => void; intl: IntlShape }) {
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <View style={{ gap: 8 }}>
      <Pressable
        accessibilityRole="button" accessibilityState={{ expanded }}
        accessibilityLabel={msg(intl, 'overview.scope.choose')}
        accessibilityHint={msg(intl, 'overview.scope', { currency })}
        onPress={() => setExpanded((previous) => !previous)}
        style={{
          minHeight: 44, alignSelf: 'flex-start', paddingHorizontal: 16, justifyContent: 'center',
          borderRadius: Radii.pill, borderWidth: 1, borderColor: theme.borderInteractive,
          backgroundColor: theme.bgSurface,
        }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {msg(intl, 'overview.scope', { currency })} ⌄
        </Text>
      </Pressable>
      {expanded ? (
        <ChipGroup
          label={msg(intl, 'overview.scope.choose')}
          options={choices.map((value) => ({ value, label: value }))}
          selected={[currency]}
          onSelect={(next) => { choose(next); setExpanded(false); }}
        />
      ) : null}
    </View>
  );
}

function Totals({
  data, hidden, explain, intl,
}: { data: NetWorth; hidden: boolean; explain: (part: FinancialPart) => void; intl: IntlShape }) {
  const theme = useTheme();
  const amounts = [
    { part: 'netWorth', label: 'overview.netWorth', value: data.netWorth },
    { part: 'assets', label: 'overview.assets', value: data.totalAssets },
    { part: 'liabilities', label: 'overview.liabilities', value: data.totalLiabilities },
  ] as const;
  return (
    <View style={{ gap: Spacing.three }}>
      {amounts.map(({ part, label, value }) => {
        const number = amountTexts(intl, { value, currency: data.currency, masked: hidden }).label;
        const a11y = [
          msg(intl, label), number,
          part === 'netWorth' && data.approximate ? msg(intl, 'valueStatus.estimated') : '',
        ].filter(Boolean).join(', ');
        return (
          <SectionCard key={part} title={msg(intl, label)}>
            <Pressable
              accessibilityRole="button" accessibilityLabel={a11y}
              accessibilityHint={msg(intl, 'overview.explain')}
              onPress={() => explain(part)}
              style={{ minHeight: 44, justifyContent: 'center' }}
            >
              <Amount value={value} currency={data.currency} masked={hidden} style={{
                  ...(part === 'netWorth' ? TypeScale.displayMedium : TypeScale.headingMedium),
                  color: part === 'liabilities' ? theme.statusLoss : theme.textPrimary,
                }}
              />
            </Pressable>
            {part === 'netWorth' && data.approximate ? (
              <ValueStatusBadge status="ESTIMATED" />
            ) : null}
            {part === 'netWorth' && !data.complete ? (
              <Pressable accessibilityRole="button" onPress={() => explain('netWorth')}
                style={{ minHeight: 44, justifyContent: 'center' }}>
                <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
              </Pressable>
            ) : null}
            {part === 'liabilities' ? largestLiabilities(data).slice(0, 2).map((account) => (
              <View key={account.accountId} style={{
                flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8,
              }}>
                <InfoText>{account.name}</InfoText>
                <Amount value={account.value} currency={data.currency} masked={hidden} style={{ ...TypeScale.caption, color: theme.textSecondary }} />
              </View>
            )) : null}
          </SectionCard>
        );
      })}
    </View>
  );
}

function SectionTiles({
  data, hidden, intl, desktop,
}: { data: NetWorth; hidden: boolean; intl: IntlShape; desktop: boolean }) {
  const theme = useTheme();
  if (!data.sections.length) return null;
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
      {data.sections.map((section) => (
        <View key={section.section} style={{
          width: desktop ? '23%' : '47%', minWidth: 140, flexGrow: 1,
        }}>
          <SectionCard title={msg(intl,
            Object.prototype.hasOwnProperty.call(sectionLabels, section.section)
              ? sectionLabels[section.section] : 'overview.unknownSection',
          )}>
            <Amount value={section.subtotal} currency={data.currency} masked={hidden}
              valueKnown={section.valueStatus !== 'UNKNOWN'}
              style={{ ...TypeScale.numberLarge, color: theme.textPrimary }} />
            <ValueStatusBadge status={section.valueStatus} />
            {!section.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
          </SectionCard>
        </View>
      ))}
    </View>
  );
}

function PortfoliosCard({
  items, hidden, intl,
}: { items: readonly Portfolio[]; hidden: boolean; intl: IntlShape }) {
  const theme = useTheme();
  return (
    <SectionCard title={msg(intl, 'overview.portfolios')}>
      {items.length === 0 ? <InfoText>{msg(intl, 'overview.portfolio.empty')}</InfoText> : null}
      {items.map((item) => (
        <View key={item.id} style={{
          flexDirection: 'row', alignItems: 'flex-start', gap: 8, paddingVertical: 10,
          borderBottomWidth: 1, borderBottomColor: theme.borderDefault,
        }}>
          <InstitutionLogo name={item.name} logoUrl={item.logoUrl}
            personalAssetsDefault={item.personalAssetsDefault} />
          <View style={{ flex: 1, minWidth: 0, gap: 4 }}>
            <Text style={{ ...TypeScale.labelMedium, fontWeight: '600', color: theme.textPrimary }}>
              {item.name}
            </Text>
            <InfoText>
              {item.personalAssetsDefault
                ? msg(intl, 'overview.portfolio.personalAssets')
                : msg(intl, 'overview.portfolio.subtitle', {
                    count: item.activeAccountCount, currency: item.portfolioCurrency,
                  })}
            </InfoText>
            <ValueStatusBadge status={item.valueStatus} />
            {!item.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
          </View>
          <View style={{ alignItems: 'flex-end', minWidth: 0, flexShrink: 1, gap: 6 }}>
            <Amount value={item.total} currency={item.portfolioCurrency} masked={hidden}
              valueKnown={item.valueStatus !== 'UNKNOWN'}
              style={TypeScale.numberLarge} />
            {item.portfolioCurrency !== item.workspaceCurrency ? (
              <Amount value={item.totalInWorkspaceCurrency} currency={item.workspaceCurrency}
                masked={hidden} approx valueKnown={item.valueStatus !== 'UNKNOWN'}
                style={{ ...TypeScale.caption, color: theme.textSecondary }} />
            ) : null}
          </View>
        </View>
      ))}
    </SectionCard>
  );
}

function CashFlowCard({
  data, currency, hidden, intl,
}: { data: CashFlow; currency: string; hidden: boolean; intl: IntlShape }) {
  const theme = useTheme();
  const month = intl.formatDate(new Date(data.month + '-01T12:00:00Z'), {
    month: 'long', timeZone: 'UTC',
  });
  const entries = [
    ['overview.cashFlow.income', currencyAmount(data.income, currency)],
    ['overview.cashFlow.spending', currencyAmount(data.spending, currency)],
    ['overview.cashFlow.saving', currencyAmount(data.saving, currency)],
  ] as const;
  const pending = currencyAmount(data.pendingReview, currency);
  return (
    <SectionCard title={msg(intl, 'overview.cashFlow.title', { month })}>
      {entries.map(([label, entry]) => (
        <View key={label} style={{
          flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8,
        }}>
          <InfoText>{msg(intl, label)}</InfoText>
          <Amount value={entry?.amount ?? null} valueKnown={entry?.valueKnown ?? false}
            currency={currency} masked={hidden} style={TypeScale.numberLarge} />
        </View>
      ))}
      {pending?.valueKnown && hasNonZeroAmount(pending.amount) ? (
        <View style={{ padding: Spacing.compact, backgroundColor: theme.statusWarningSubtle }}>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.statusWarning }}>
            {msg(intl, 'overview.cashFlow.pendingReview', {
              amount: amountTexts(intl, { value: pending.amount, currency, masked: hidden }).display,
            })}
          </Text>
        </View>
      ) : null}
      {!data.complete ? <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText> : null}
    </SectionCard>
  );
}

function ExplanationSheet({
  part, data, hidden, close, intl,
}: { part: Explanation; data?: NetWorth; hidden: boolean; close: () => void; intl: IntlShape }) {
  if (!data || !part) return null;
  const value = part === 'netWorth' ? data.netWorth
    : part === 'assets' ? data.totalAssets : data.totalLiabilities;
  const title = msg(intl, 'overview.' +
    (part === 'netWorth' ? 'netWorth' : part === 'assets' ? 'assets' : 'liabilities'));
  return (
    <Dialog title={title} visible onClose={close}>
      <Amount value={value} currency={data.currency} masked={hidden} style={TypeScale.headingMedium} />
      <InfoText>{msg(intl, 'overview.explain.asOf', {
        date: formatDate(data.asOf, intl.locale),
      })}</InfoText>
      <InfoText>{msg(intl, 'overview.explain.currency', { currency: data.currency })}</InfoText>
      {data.approximate ? <InfoText>{msg(intl, 'overview.explain.approximate')}</InfoText> : null}
      {!data.complete ? (
        <>
          <InfoText warning>{msg(intl, 'total.incomplete')}</InfoText>
          <Text accessibilityRole="header">{msg(intl, 'overview.explain.unknown')}</Text>
          {unknownValuations(data).length ? unknownValuations(data).map((account) => (
            <InfoText key={account.accountId}>{account.name}</InfoText>
          )) : <InfoText>{msg(intl, 'overview.explain.none')}</InfoText>}
        </>
      ) : <InfoText>{msg(intl, 'overview.explain.complete')}</InfoText>}
      {data.warnings.map((warning, index) => (
        <InfoText key={warning + index}>
          {msg(intl, 'overview.explain.warning', { warning })}
        </InfoText>
      ))}
    </Dialog>
  );
}

function FirstRun({
  intl, offline, onAddPortfolio,
}: { intl: IntlShape; offline: boolean; onAddPortfolio: () => void }) {
  return (
    <View style={{ gap: Spacing.three }}>
      <SectionCard title={msg(intl, 'overview.firstRun.noFigures')}>
        <InfoText>{msg(intl, 'overview.firstRun.description')}</InfoText>
      </SectionCard>
      <SectionCard title={msg(intl, 'overview.firstRun.heading')}>
        {['step1', 'step2', 'step3'].map((step, index) => (
          <InfoText key={step}>{index + 1}. {msg(intl, 'overview.firstRun.' + step)}</InfoText>
        ))}
        <ActionButton label={msg(intl, 'overview.firstRun.action')}
          onPress={onAddPortfolio} disabled={offline} />
      </SectionCard>
    </View>
  );
}

/** US-01-15 / S1: independent TanStack queries, no financial arithmetic in the UI. */
export function OverviewScreen({
  onAddPortfolio, onReviewAccount,
}: OverviewScreenProps) {
  const intl = useIntl();
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const online = useOnline();
  const [selectedCurrency, selectCurrency] = useState<string | null>(null);
  const [explanation, setExplanation] = useState<Explanation>(null);
  const hidden = useAmountsHidden();
  const month = useMemo(() => monthKey(new Date()), []);
  const workspace = useWorkspace();
  const portfolios = usePortfolios();
  const currency = selectedCurrency ?? workspace.data?.currency;
  const netWorth = useNetWorth(currency);
  const accounts = useAccounts();
  const cashFlow = useCashFlow(month, currency);
  const results = [workspace, portfolios, netWorth, accounts, cashFlow];
  const refreshing = results.some((result) => result.isRefetching);
  const refresh = () => { void Promise.allSettled(results.map((result) => result.refetch())); };
  const portfolioItems = portfolios.data ?? [];
  const unreconciled = unreconciledAccounts(accounts.data ?? []);
  const firstRun = Boolean(
    portfolios.isSuccess && netWorth.isSuccess && netWorth.data &&
    isFirstRunEmpty(portfolioItems, netWorth.data),
  );
  const noAccounts = portfolioItems.length > 0 &&
    portfolioItems.every((portfolio) => portfolio.activeAccountCount === 0);
  const hour = new Date().getHours();
  const period = hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening';

  return (
    <ScrollView
      testID="overview-screen" style={{ flex: 1, backgroundColor: theme.bgCanvas }}
      contentContainerStyle={{
        padding: width >= 1024 ? Spacing.wide : Spacing.three,
        gap: Spacing.three,
      }}
      refreshControl={Platform.OS === 'web' ? undefined : (
        <RefreshControl refreshing={refreshing} onRefresh={refresh} />
      )}
    >
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
        <View style={{ gap: 4 }}>
          <InfoText>{msg(intl, 'overview.greeting.' + period)}</InfoText>
          <Text accessibilityRole="header"
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}>
            {msg(intl, 'overview.title')}
          </Text>
        </View>

      </View>
      {Platform.OS === 'web' ? (
        <Pressable accessibilityRole="button" accessibilityLabel={msg(intl, 'overview.refresh')}
          onPress={refresh} style={{ minHeight: 44, alignSelf: 'flex-start' }}>
          <Text style={{ color: theme.brandLink }}>{msg(intl, 'overview.refresh')}</Text>
        </Pressable>
      ) : null}
      {!online ? <OfflineBanner
        lastUpdated={netWorth.dataUpdatedAt ? new Date(netWorth.dataUpdatedAt).toISOString() : undefined}
      /> : null}
      {workspace.isPending ? <LoadingSkeleton count={1} /> : null}
      {workspace.isError ? <ErrorBanner error={workspace.error}
        onRetry={() => void workspace.refetch()} /> : null}
      {workspace.data ? (
        <>
          <TabStrip name={workspace.data.name} intl={intl} offline={!online}
            onAddPortfolio={onAddPortfolio} />
          <CurrencyScope
            currency={currency ?? workspace.data.currency}
            choices={scopeCurrencies(workspace.data.currency)}
            choose={selectCurrency} intl={intl}
          />
        </>
      ) : null}
      {netWorth.data ? (
        <InfoText>{msg(intl, 'overview.asOf', {
          date: formatDate(netWorth.data.asOf, intl.locale),
        })}</InfoText>
      ) : null}
      {accounts.isError ? <ErrorBanner error={accounts.error}
        onRetry={() => void accounts.refetch()} /> : null}
      {unreconciled.length > 0 ? (
        <View accessibilityRole="alert" style={{
          borderLeftWidth: 4, borderLeftColor: theme.statusWarning,
          borderRadius: Radii.control, backgroundColor: theme.statusWarningSubtle,
          padding: Spacing.compact,
        }}>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
            {msg(intl, 'overview.dataStatus.unreconciled', { count: unreconciled.length })}
          </Text>
          {unreconciled.length === 1 && onReviewAccount ? (
            <Pressable accessibilityRole="button" onPress={() => onReviewAccount(unreconciled[0].id)}
              style={{ minHeight: 44, justifyContent: 'center' }}>
              <Text style={{ color: theme.brandLink }}>
                {msg(intl, 'overview.warning.review')}
              </Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}
      {firstRun ? <FirstRun intl={intl} offline={!online} onAddPortfolio={onAddPortfolio} /> : (
        <>
          {netWorth.isPending ? <LoadingSkeleton count={4} height={28} /> : null}
          {netWorth.isError ? <ErrorBanner error={netWorth.error}
            onRetry={() => void netWorth.refetch()} /> : null}
          {netWorth.data ? (
            <View style={{ gap: Spacing.three }}>
              <Totals data={netWorth.data} hidden={hidden} explain={setExplanation} intl={intl} />
              {noAccounts ? <InfoText>{msg(intl, 'overview.noAccounts')}</InfoText> : null}
              <SectionTiles data={netWorth.data} hidden={hidden} intl={intl} desktop={width >= 1024} />
            </View>
          ) : null}
        </>
      )}
      {!firstRun ? (
        <View style={{ flexDirection: width >= 1024 ? 'row' : 'column', gap: Spacing.three }}>
          <View style={{ flex: 1, minWidth: 0 }}>
            {portfolios.isPending ? <LoadingSkeleton count={3} /> : null}
            {portfolios.isError ? <ErrorBanner error={portfolios.error}
              onRetry={() => void portfolios.refetch()} /> : null}
            {portfolios.data ? <PortfoliosCard items={portfolioItems} hidden={hidden} intl={intl} /> : null}
          </View>
          <View style={{ flex: 1, minWidth: 0 }}>
            {cashFlow.isPending ? <LoadingSkeleton count={3} /> : null}
            {cashFlow.isError ? <ErrorBanner error={cashFlow.error}
              onRetry={() => void cashFlow.refetch()} /> : null}
            {cashFlow.data ? <CashFlowCard data={cashFlow.data} intl={intl}
              currency={currency ?? cashFlow.data.income[0]?.currency ?? ''}
              hidden={hidden} /> : null}
          </View>
        </View>
      ) : null}
      <ExplanationSheet part={explanation} data={netWorth.data} hidden={hidden}
        close={() => setExplanation(null)} intl={intl} />
    </ScrollView>
  );
}
