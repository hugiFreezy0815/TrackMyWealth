import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
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

import { ApiError } from '@/api/client';
import { usePortfolioView, usePortfolios, useAccountPositions } from '@/api/queries/resources';
import {
  amountTexts,
  Amount,
  ActionButton,
  Dialog,
  EmptyState,
  ErrorBanner,
  InstitutionLogo,
  LoadingSkeleton,
  OfflineBanner,
  SectionCard,
  ValueStatusBadge,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate, formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { TabStrip } from '@/features/overview/tab-strip';

import {
  accountRows,
  accountTypeMessageKey,
  daysUntil,
  hasPositions,
  isNegative,
  sectionMessageKey,
  typeMessageKey,
  type PortfolioAccount,
  type PortfolioSection,
  type Position,
} from './model';

type Props = {
  portfolioId: string;
  overviewName: string | null;
  onOverview: () => void;
  onPortfolio: (id: string) => void;
  onAddPortfolio: () => void;
  onAddAccount: (portfolioId: string) => void;
  onImport: (portfolioId: string) => void;
  onAccount: (accountId: string) => void;
  onMissing: () => void;
};

function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}

/** Uses only data available on the API; no invented account or monetary figures. */
function PositionList({
  account,
  onAccount,
}: {
  account: PortfolioAccount;
  onAccount: (accountId: string) => void;
}) {
  const theme = useTheme();
  const intl = useIntl();
  const positions = useAccountPositions(account.accountId);
  const m = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  if (positions.isPending) return <LoadingSkeleton count={2} />;
  if (positions.isError)
    return <ErrorBanner error={positions.error} onRetry={() => void positions.refetch()} />;
  const source = positions.data;
  if (!source) return null;
  return (
    <View style={{ gap: Spacing.two }}>
      {source.positions.slice(0, 5).map((position: Position) => (
        <Pressable
          key={position.securityId}
          accessibilityRole="button"
          accessibilityLabel={m('portfolio.position.open', { name: position.displayName })}
          onPress={() => onAccount(account.accountId)}
          style={{
            minHeight: 44,
            borderBottomWidth: 1,
            borderBottomColor: theme.borderDefault,
            paddingVertical: Spacing.two,
            gap: Spacing.one,
          }}
        >
          <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
            {position.displayName}
          </Text>
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {m('portfolio.positions.quantity', {
              count: position.quantity,
              isin: position.isin ?? '',
            })}
          </Text>
          <Amount
            value={position.valueInAccountCurrency}
            currency={source.currency}
            valueKnown={position.valueStatus !== 'UNKNOWN'}
          />
          <ValueStatusBadge status={position.valueStatus} />
          {position.unrealisedGainPercent != null ? (
            <Text
              style={{
                color: isNegative(position.unrealisedGainPercent)
                  ? theme.statusLoss
                  : theme.statusGain,
              }}
            >
              {m('portfolio.position.gain', { percent: position.unrealisedGainPercent === '0'
                  ? '0' : isNegative(position.unrealisedGainPercent)
                    ? '−' + position.unrealisedGainPercent.slice(1)
                    : '+' + position.unrealisedGainPercent })}
            </Text>
          ) : (
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
              {position.priceCurrency && position.priceCurrency !== source.currency
                ? m('portfolio.position.foreignGain', { currency: position.priceCurrency })
                : m('portfolio.position.noBasis')}
            </Text>
          )}
          {position.costBasisEstimated ? (
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
              {m('portfolio.position.estimated')}
            </Text>
          ) : null}
        </Pressable>
      ))}
      {source.positions.length > 5 ? (
        <ActionButton
          label={m('portfolio.positions.all', { count: source.positions.length })}
          onPress={() => onAccount(account.accountId)}
        />
      ) : null}
    </View>
  );
}

function PortfolioAccountRow({
  account,
  section,
  portfolioCurrency,
  onAccount,
}: {
  account: PortfolioAccount;
  section: PortfolioSection;
  portfolioCurrency: string;
  onAccount: (accountId: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const m = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const valueDate = account.valueSourceDate
    ? formatDate(account.valueSourceDate, intl.locale)
    : null;
  const dueDays =
    section.section === 'CARDS' && account.dueDate ? daysUntil(account.dueDate, new Date()) : null;
  const extra =
    section.section === 'PROPERTY_AND_MORTGAGE' && account.accountType === 'CUSTOM_ASSET'
      ? m('portfolio.account.manual', { date: valueDate ?? '' })
      : m(accountTypeMessageKey(account.accountType));
  const spokenAmount = amountTexts(intl, {
    value: account.balance,
    currency: account.currency,
    valueKnown: account.valueKnown && account.valueStatus !== 'UNKNOWN',
    masked: hidden,
  }).label;
  return (
    <View
      style={{
        paddingStart: account.linkedPropertyAccountId ? Spacing.three : 0,
        gap: Spacing.two,
        borderBottomWidth: 1,
        borderBottomColor: theme.borderDefault,
      }}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={[account.name, spokenAmount, extra].join(', ')}
        onPress={() => onAccount(account.accountId)}
        style={{ minHeight: 56, paddingVertical: Spacing.two, gap: Spacing.one }}
      >
        <View
          style={{
            flexDirection: 'row',
            gap: Spacing.two,
            flexWrap: 'wrap',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
          }}
        >
          <View style={{ flex: 1, minWidth: 120, gap: Spacing.one }}>
            <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
              {account.name}
            </Text>
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
              {extra}
              {account.maskedIdentifier ? ' · ' + account.maskedIdentifier : ''}
              {valueDate && account.accountType !== 'CUSTOM_ASSET'
                ? ' · ' + m('portfolio.asOf', { date: valueDate })
                : ''}
              {account.statementDate
                ? ' · ' +
                  m('portfolio.card.statement', {
                    date: formatDate(account.statementDate, intl.locale),
                  })
                : ''}
            </Text>
          </View>
          <View style={{ alignItems: 'flex-end', gap: Spacing.one }}>
            <Amount
              value={account.balance}
              currency={account.currency}
              valueKnown={account.valueKnown && account.valueStatus !== 'UNKNOWN'}
              style={{
                ...TypeScale.numberLarge,
                color: isNegative(account.balance) ? theme.statusLoss : theme.textPrimary,
              }}
            />
            {account.currency !== portfolioCurrency && account.valueInPortfolioCurrency !== null ? (
              <Amount
                approx
                value={account.valueInPortfolioCurrency}
                valueKnown={account.valueKnown && account.valueStatus !== 'UNKNOWN'}
                currency={portfolioCurrency}
                style={TypeScale.caption}
              />
            ) : null}
          </View>
        </View>
      </Pressable>
      <View
        style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
      >
        <ValueStatusBadge status={account.valueStatus} valueSourceDate={account.valueSourceDate} />
        {account.reconciliationStatus === 'RECONCILED' ? (
          <Text style={{ ...TypeScale.caption, color: theme.statusGain }}>
            {m('portfolio.reconciled')}
          </Text>
        ) : null}
        {dueDays !== null ? (
          <Text
            style={{
              ...TypeScale.caption,
              color: dueDays < 0 ? theme.statusWarning : theme.textSecondary,
            }}
          >
            {dueDays < 0
              ? m('portfolio.card.overdue', {
                  date: formatDate(account.dueDate ?? '', intl.locale),
                })
              : m('portfolio.card.due', { count: dueDays })}
          </Text>
        ) : null}
        {account.totalReturn != null ? (
          <Text
            style={{
              ...TypeScale.caption,
              color: isNegative(account.totalReturn) ? theme.statusLoss : theme.statusGain,
            }}
          >
            {m('portfolio.totalReturn', { percent: account.totalReturn })}
          </Text>
        ) : null}
      </View>
      {hasPositions(account, section) ? (
        <PositionList account={account} onAccount={onAccount} />
      ) : null}
    </View>
  );
}

/** S2: server-ordered sections. Header survives a failed view query via cached S1 item. */
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
  const hidden = useAmountsHidden();
  const online = useOnline();
  const { width } = useWindowDimensions();
  const desktop = width >= 1024;
  const m = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const portfolios = usePortfolios();
  const viewQuery = usePortfolioView(portfolioId);
  const view = viewQuery.data;
  const fallback = portfolios.data?.find((item) => item.id === portfolioId);
  const [explanation, setExplanation] = useState<PortfolioSection | 'total' | null>(null);
  const [advanced, setAdvanced] = useState(false);
  const refreshing = viewQuery.isRefetching || portfolios.isRefetching;
  const refresh = () => {
    void Promise.all([viewQuery.refetch(), portfolios.refetch()]);
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
  const selectedTotal = view?.total ?? fallback?.total ?? null;
  const currency = view?.portfolioCurrency ?? fallback?.portfolioCurrency ?? '';
  const workspaceCurrency = view?.workspaceCurrency ?? fallback?.workspaceCurrency ?? '';
  const totalStatus = view?.valueStatus ?? fallback?.valueStatus ?? 'UNKNOWN';
  const complete = view?.complete ?? fallback?.complete ?? false;
  const name = view?.name ?? fallback?.name ?? m('portfolio.unknownName');
  const personal = view?.personalAssetsDefault ?? fallback?.personalAssetsDefault ?? false;
  const type = view?.portfolioType ?? fallback?.portfolioType ?? '';
  const converted = view?.totalInWorkspaceCurrency ?? fallback?.totalInWorkspaceCurrency ?? null;

  const explanations =
    explanation === 'total'
      ? {
          title: m('portfolio.total'),
          amount: selectedTotal,
          asOf: view?.asOf ?? fallback?.asOf,
          warnings: view?.warnings ?? [],
          complete,
          status: totalStatus,
        }
      : explanation
        ? {
            title: m(sectionMessageKey(explanation.section)),
            amount: explanation.subtotal,
            asOf: view?.asOf,
            warnings: [],
            complete: explanation.complete,
            status: explanation.valueStatus,
          }
        : null;
  const main = (
    <View style={{ gap: Spacing.three, flex: 1, minWidth: 0 }}>
      <SectionCard title={name}>
        <View
          style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: Spacing.two }}
        >
          <InstitutionLogo
            name={name}
            logoUrl={view?.logoUrl ?? fallback?.logoUrl ?? null}
            personalAssetsDefault={personal}
          />
          {typeMessageKey(type) ? (
            <Text style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
              {m(typeMessageKey(type)!)}
            </Text>
          ) : null}
        </View>
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {view?.institutionName || fallback?.institutionName
            ? m('portfolio.subtitle', {
                institution: view?.institutionName ?? fallback?.institutionName ?? '',
                country: fallback?.country ?? '',
                currency,
              })
            : m('portfolio.currencyOnly', { currency })}
        </Text>
        <Pressable
          accessibilityRole="button"
          testID="portfolio-total-action"
          accessibilityLabel={m('portfolio.totalExplain', {
            name,
            amount: amountTexts(intl, {
              value: selectedTotal,
              currency,
              masked: hidden,
              valueKnown: totalStatus !== 'UNKNOWN',
            }).label,
          })}
          onPress={() => setExplanation('total')}
          style={{ minHeight: 48, justifyContent: 'center' }}
        >
          <Amount
            value={selectedTotal}
            currency={currency}
            testID="portfolio-total"
            valueKnown={totalStatus !== 'UNKNOWN'}
            style={{
              ...TypeScale.headingLarge,
              color: isNegative(selectedTotal) ? theme.statusLoss : theme.textPrimary,
            }}
          />
        </Pressable>
        <ValueStatusBadge status={totalStatus} />
        {!complete ? (
          <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
            {m('total.incomplete')}
          </Text>
        ) : null}
        {currency !== workspaceCurrency && converted != null ? (
          <Amount
            approx
            value={converted}
            currency={workspaceCurrency}
            valueKnown={totalStatus !== 'UNKNOWN'}
            style={TypeScale.bodyMedium}
          />
        ) : null}
        {view?.conversionRate && view.conversionRateDate ? (
          <Pressable
            accessibilityRole="button"
            onPress={() => setAdvanced(!advanced)}
            style={{ minHeight: 44, justifyContent: 'center' }}
          >
            <Text style={{ color: theme.brandLink }}>{m('portfolio.fx.details')}</Text>
          </Pressable>
        ) : null}
        {advanced && view?.conversionRate && view.conversionRateDate ? (
          <Text
            style={{ color: view.conversionRateStale ? theme.statusWarning : theme.textSecondary }}
          >
            {m('portfolio.fx.rate', {
              from: currency,
              to: workspaceCurrency,
              rate: view.conversionRate,
              date: formatDate(view.conversionRateDate, intl.locale),
            })}
            {view.conversionRateCarriedForward ? ' · ' + m('portfolio.fx.carried') : ''}
          </Text>
        ) : null}
      </SectionCard>
      {view?.sections.length === 0 ? (
        <EmptyState
          title={m('portfolio.empty.title', { name })}
          description={m('portfolio.empty.body')}
          actionLabel={m('portfolio.addAccount')}
          onAction={() => onAddAccount(portfolioId)}
        />
      ) : null}
      {view?.sections.map((section, index) => (
        <SectionCard key={index} title={m(sectionMessageKey(section.section))}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={m('portfolio.section.explain', {
              section: m(sectionMessageKey(section.section)),
              amount: amountTexts(intl, {
                value: section.subtotal,
                currency,
                valueKnown: section.valueStatus !== 'UNKNOWN',
                masked: hidden,
              }).label,
            })}
            onPress={() => setExplanation(section)}
            style={{ minHeight: 44, justifyContent: 'center', gap: Spacing.one }}
          >
            <Amount
              value={section.subtotal}
              currency={currency}
              valueKnown={section.valueStatus !== 'UNKNOWN'}
              style={{
                ...TypeScale.headingMedium,
                color: isNegative(section.subtotal) ? theme.statusLoss : theme.textPrimary,
              }}
            />
          </Pressable>
          <ValueStatusBadge status={section.valueStatus} />
          {!section.complete ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {m('total.incomplete')}
            </Text>
          ) : null}
          {accountRows(section).map((account) => (
            <PortfolioAccountRow
              key={account.accountId}
              account={account}
              section={section}
              portfolioCurrency={currency}
              onAccount={onAccount}
            />
          ))}
        </SectionCard>
      ))}
      {view && !personal ? (
        <SectionCard title={m('portfolio.source.title')}>
          <Text style={{ color: theme.textSecondary }}>
            {view.lastImport
              ? m('portfolio.source.last', {
                  format: view.lastImport.fileFormat,
                  date: formatDateTime(view.lastImport.committedAt, intl.locale),
                  count: view.lastImport.transactionCount,
                })
              : m('portfolio.source.none')}
          </Text>
          <ActionButton
            label={m('portfolio.import')}
            onPress={() => onImport(portfolioId)}
            disabled={!online}
          />
        </SectionCard>
      ) : null}
    </View>
  );
  const aside = view ? (
    <View style={{ width: '100%' }}>
      <SectionCard title={m('portfolio.details.title')}>
        <Text style={{ color: theme.textSecondary }}>
          {m('portfolio.details.type', {
            type: typeMessageKey(type) ? m(typeMessageKey(type)!) : '',
          })}
        </Text>
        <Text style={{ color: theme.textSecondary }}>
          {m('portfolio.details.currency', { currency })}
        </Text>
        <Text style={{ color: theme.textSecondary }}>
          {m('portfolio.details.accounts', {
            count: view.sections.reduce((n, s) => n + s.accounts.length, 0),
          })}
        </Text>
      </SectionCard>
    </View>
  ) : null;

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
        <ActionButton label={m('portfolio.refresh')} onPress={refresh} />
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
        <Text style={{ color: theme.textSecondary }}>{m('portfolio.refreshing')}</Text>
      ) : null}
      {missing ? (
        <View style={{ gap: Spacing.two }}>
          <ErrorBanner message={m('portfolio.missing')} />
          <ActionButton label={m('portfolio.back')} onPress={onMissing} />
        </View>
      ) : viewQuery.isError ? (
        <ErrorBanner error={viewQuery.error} onRetry={() => void viewQuery.refetch()} />
      ) : null}
      {!missing && (view || fallback) ? (
        desktop ? (
          <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: Spacing.four }}>
            {main}
            <View style={{ width: 320 }}>{aside}</View>
          </View>
        ) : (
          main
        )
      ) : null}
      <Dialog
        title={explanations?.title ?? ''}
        visible={explanation !== null}
        onClose={() => setExplanation(null)}
      >
        {explanations ? (
          <View style={{ gap: Spacing.two }}>
            <Amount
              value={explanations.amount}
              currency={currency}
              valueKnown={explanations.status !== 'UNKNOWN'}
              style={TypeScale.headingMedium}
            />
            <ValueStatusBadge status={explanations.status} />
            {explanations.asOf ? (
              <Text style={{ color: theme.textSecondary }}>
                {m('portfolio.asOf', { date: formatDate(explanations.asOf, intl.locale) })}
              </Text>
            ) : null}
            {!explanations.complete ? (
              <Text style={{ color: theme.statusWarning }}>{m('total.incomplete')}</Text>
            ) : null}
            {explanations.warnings.map((warning, i) => (
              <Text key={i} style={{ color: theme.textSecondary }}>
                {m('portfolio.warning.generic')}
              </Text>
            ))}
          </View>
        ) : null}
      </Dialog>
    </ScrollView>
  );
}
