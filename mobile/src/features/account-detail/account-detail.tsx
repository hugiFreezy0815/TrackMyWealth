import { useCallback, useEffect, useRef, useState, type ComponentRef } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Platform, Pressable, Text, View } from 'react-native';

import { ApiError, NetworkError, SessionRefreshUnavailableError } from '@/api/client';
import { typedApi } from '@/api/typed-api';
import { problemReaction } from '@/api/problems';
import { retryVersionRequired, useConflict } from '@/api/queries/conflict';
import { invalidateFinancialData, invalidateFromFirstPage } from '@/api/queries/invalidation';
import { queryKeys, queryRoots } from '@/api/queries/keys';
import {
  useAccount,
  useAccountArchiveAction,
  useAccountPositions,
  useAccountTransactions,
  useAccounts,
  useArchivedAccounts,
  useCategories,
  usePortfolioView,
  useChangeTransactionCategory,
  useUncategorizedCount,
  useUpdateAccountOpeningDate,
  type TransactionSort,
} from '@/api/queries/resources';
import {
  ActionButton,
  Amount,
  ChipGroup,
  ConflictDialog,
  DateField,
  Dialog,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  OfflineBanner,
  ValueStatusBadge,
  localDateToIso,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { validationErrors } from '@/features/add-flow/model';
import { LiabilityDetail } from '@/features/liability-detail/liability-detail';
import type { OpenRepaymentTransaction } from '@/features/liability-detail/repayments';
import { PositionView } from '@/features/portfolio-view/position-view';
import { useSavingsPocketLink } from '@/features/savings-pocket/link-pocket';
import { showToast } from '@/features/shell/toast';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import {
  ActionsDialog,
  ArchiveDialog,
  CategoryPickerDialog,
  SortDialog,
  TransactionActionsDialog,
  ValueExplanationSheet,
  sortMessageId,
} from './account-dialogs';
import { AccountHeaderCard, ArchivedBanner } from './account-header';
import {
  accountTypeLabel,
  accountValuation,
  valueBasisExplanation,
  hasUnknownCategory,
  uniqueTransactions,
  presentTransaction,
  transactionPurpose,
  type AccountListItem,
  type AccountSummary,
  type Transaction,
} from './model';
import { copyTransactionPurpose } from './copy-purpose';
import { isAccessFailure, isNotFound } from './access';
import { useReversalDates } from './reversal-dates';
import { useRowFocus } from './row-focus';
import { TransactionResults } from './transaction-results';

export type AccountDetailProps = {
  accountId: string;
  onBack: () => void;
  initialTab?: 'positions' | 'activity';
  onTransaction?: (id: string) => void;
  onRepaymentTransaction?: OpenRepaymentTransaction;
  liabilityInAside?: boolean;
  /** The selected transaction in a desktop S4 panel, if any. */
  selectedTransactionId?: string | null;
  onImport: () => void;
  /** Native pull-to-refresh shares the same single SectionList scroll owner. */
  refreshing?: boolean;
  onRefresh?: () => void;
};

type OpenDialog = 'actions' | 'sort' | 'archive' | 'value' | 'openingDate' | null;

/** A stale S1/S2 value is only safe as a fallback while the server is unreachable or failing. */
const isRecoverableAccountFailure = (error: unknown) =>
  error instanceof NetworkError ||
  (error instanceof ApiError && error.status >= 500 && error.status < 600);

/** Keep resource-specific API problems inline, even when they use a 5xx status. */
function isSharedReadFailure(error: unknown) {
  if (error instanceof NetworkError || error instanceof SessionRefreshUnavailableError) return true;
  if (!(error instanceof ApiError) || !isRecoverableAccountFailure(error)) return false;
  const problem = problemReaction(error);
  return (
    problem.reaction === 'REPORT' && (problem.code === undefined || problem.code === 'INTERNAL')
  );
}

/** Pull-to-refresh: every financial query reloads, paged lists from page 0, filter and sort kept. */
export function useAccountRefresh() {
  const client = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await invalidateFinancialData(client);
    } finally {
      setRefreshing(false);
    }
  }, [client]);
  return { refreshing, refresh };
}

export function AccountDetail({
  accountId,
  onBack,
  onTransaction,
  onRepaymentTransaction,
  liabilityInAside = false,
  selectedTransactionId,
  initialTab = 'activity',
  onImport,
  refreshing = false,
  onRefresh,
}: AccountDetailProps) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const client = useQueryClient();
  const [uncategorized, setUncategorized] = useState(false);
  const [sort, setSort] = useState<TransactionSort>('bookingDate,desc');
  const [tab, setTab] = useState(initialTab);
  const [actionRowId, setActionRowId] = useState<string | null>(null);
  const deferredRowAction = useRef<(() => void) | null>(null);
  const [categoryTarget, setCategoryTarget] = useState<Transaction | null>(null);
  const [pendingCategoryId, setPendingCategoryId] = useState<string | null>(null);
  const [reloadingConflict, setReloadingConflict] = useState(false);
  const [openingDateReloadError, setOpeningDateReloadError] = useState<{ error: unknown } | null>(
    null,
  );
  const openingDateReloadInFlight = useRef(false);
  const writeInFlight = useRef(false);
  const [dialog, setDialog] = useState<OpenDialog>(null);
  const [openingDateDraft, setOpeningDateDraft] = useState<{
    account: AccountSummary;
    openedAt: string | null;
  } | null>(null);
  const [writeError, setWriteError] = useState<unknown>(null);
  const retryWrite = useRef<(() => void) | null>(null);
  const conflict = useConflict();

  const account = useAccount(accountId);
  const summary = account.data;
  const archived = summary?.status === 'ARCHIVED';
  // PO 2026-11-23: S3 loads the S2 view itself when it is not cached (cold start, deep link).
  const portfolioView = usePortfolioView(summary?.portfolioId ?? '', { enabled: !archived });
  const maskedIdentifier =
    !archived && !isAccessFailure(portfolioView.error)
      ? portfolioView.data?.sections
          .flatMap((section) => section.accounts)
          .find((row) => row.accountId === accountId)?.maskedIdentifier
      : null;
  const hasLedger = !!summary?.hasTransactions;
  const showingPositions = !!summary?.holdsPositions && tab === 'positions';
  const showingTransactions = hasLedger && !showingPositions;
  const activeAccounts = useAccounts(summary?.portfolioId, {
    enabled: !!summary?.portfolioId,
  });
  const archivedAccounts = useArchivedAccounts(summary?.portfolioId ?? '', archived);
  // The overview's account list may have loaded before the detail deep link. Its server-provided
  // value remains useful when the detail fetch or its scoped value query fails offline.
  const cachedValue = client
    .getQueriesData<AccountListItem[]>({ queryKey: [queryRoots.accounts] })
    .flatMap(([, rows]) => (Array.isArray(rows) ? rows : []))
    .find((row) => row?.id === accountId && (!summary || row.archived === archived));
  const value =
    accountValuation(summary, activeAccounts.data, archivedAccounts.data) ?? cachedValue;
  const categories = useCategories();
  const count = useUncategorizedCount(accountId, hasLedger);
  const transactions = useAccountTransactions(accountId, uncategorized, sort, hasLedger);
  const positions = useAccountPositions(accountId, !!summary?.holdsPositions);
  const categoryWrite = useChangeTransactionCategory(accountId);
  const archiveWrite = useAccountArchiveAction(accountId);
  const openingDateWrite = useUpdateAccountOpeningDate(accountId);
  const today = localDateToIso(new Date());
  const openingDateInFuture = !!openingDateDraft?.openedAt && openingDateDraft.openedAt > today;
  const openingDateRejected = !!validationErrors(writeError).openedAt;
  const openingDateNeedsReload = reloadingConflict || openingDateReloadError !== null;
  const openingDateInvalid = openingDateInFuture || openingDateRejected;
  // #466: offered in the actions menu; the account lists it needs are read when the menu opens.
  const pocket = useSavingsPocketLink({
    account: summary,
    disabled: !online || archiveWrite.isPending,
    context: 'account',
    load: dialog === 'actions',
  });
  const records = uniqueTransactions(transactions.data?.pages ?? []);
  const reversalDates = useReversalDates(
    accountId,
    records,
    showingTransactions && !isAccessFailure(account.error) && !isAccessFailure(transactions.error),
  );
  const readableTransactions = showingTransactions && !isAccessFailure(transactions.error);
  const actionRow =
    readableTransactions && actionRowId ? records.find((row) => row.id === actionRowId) : undefined;
  const recordIds = records.map((record) => record.id);
  const headingRef = useRef<ComponentRef<typeof Text>>(null);
  const rowFocus = useRowFocus(recordIds, !transactions.isFetching, headingRef);
  const total = transactions.data?.pages[0]?.page.totalElements ?? 0;
  const countValue = count.data ?? 0;
  const locked =
    !online || archived || reloadingConflict || categoryWrite.isPending || archiveWrite.isPending;

  const restartActivityQuery = useCallback(
    (nextFilter: boolean, nextSort: TransactionSort) => {
      const filters = {
        queryKey: queryKeys.accountTransactions(accountId, {
          uncategorized: nextFilter,
          sort: nextSort,
        }),
        exact: true,
      };
      // A request for the previous visit must not repopulate pages after they are trimmed.
      void client.cancelQueries(filters);
      // Refetch when the new selection becomes active, keeping page 0 available offline.
      return invalidateFromFirstPage(client, { ...filters, refetchType: 'none' });
    },
    [accountId, client],
  );

  function changeActivityView(nextFilter: boolean, nextSort: TransactionSort) {
    if (nextFilter === uncategorized && nextSort === sort) return;
    rowFocus.reset();
    void restartActivityQuery(nextFilter, nextSort);
    setUncategorized(nextFilter);
    setSort(nextSort);
  }

  // Spec S3: fall back to All at page 0 once the server count reaches zero.
  useEffect(() => {
    if (!uncategorized || count.data !== 0) return;
    let active = true;
    void restartActivityQuery(false, sort).then(() => {
      if (active) setUncategorized(false);
    });
    return () => {
      active = false;
    };
  }, [count.data, restartActivityQuery, sort, uncategorized]);

  // Spec S3 row 4: a category id the cached list lacks triggers one reload of /categories.
  const reloadedCategories = useRef(false);
  const unknownCategory =
    categories.isSuccess && hasUnknownCategory(records, categories.data ?? []);
  const reloadCategories = categories.refetch;
  useEffect(() => {
    if (!unknownCategory || reloadedCategories.current) return;
    reloadedCategories.current = true;
    void reloadCategories();
  }, [unknownCategory, reloadCategories]);

  // A targeted transaction read must not rely on a paged list: the row may move
  // outside the current filter, or past the loaded page.
  async function reloadTransactionVersion(transactionId: string) {
    const latest = await typedApi.get('/api/v1/accounts/{accountId}/transactions/{transactionId}', {
      params: { accountId, transactionId },
    });
    client.setQueryData(queryKeys.transaction(accountId, transactionId), latest);
    setCategoryTarget((current) =>
      current?.id === transactionId
        ? { ...current, version: latest.version, categoryId: latest.categoryId }
        : current,
    );
    return latest;
  }

  async function reloadAccountVersion() {
    const latest = await typedApi.get('/api/v1/accounts/{accountId}', {
      params: { accountId },
    });
    client.setQueryData(queryKeys.account(accountId), latest);
    return latest;
  }

  async function runWrite(write: () => Promise<unknown>, conflictKey: QueryKey) {
    // Guard the event-handler gap before mutation.isPending updates.
    if (writeInFlight.current) return;
    writeInFlight.current = true;
    setWriteError(null);
    retryWrite.current = null;
    try {
      await write();
    } catch (error) {
      if (await conflict.handle(error, conflictKey)) {
        setReloadingConflict(true);
        return;
      }
      if (isNotFound(error)) {
        showToast('account.notFound');
        onBack();
        return;
      }
      retryWrite.current = () => void runWrite(write, conflictKey);
      setWriteError(error);
    } finally {
      writeInFlight.current = false;
    }
  }

  function changeCategory(categoryId: string) {
    const target = categoryTarget;
    if (!target || locked) return;
    setPendingCategoryId(categoryId);
    const shown = recordIds;
    void runWrite(async () => {
      const cached = client.getQueryData<{ version: number }>(
        queryKeys.transaction(accountId, target.id),
      );
      await retryVersionRequired(
        (version) => categoryWrite.mutateAsync({ transactionId: target.id, categoryId, version }),
        cached?.version ?? target.version,
        () => reloadTransactionVersion(target.id),
      );
      setCategoryTarget(null);
      setPendingCategoryId(null);
      // The save resolves after the list reloaded; the row may have left it.
      rowFocus.afterCategorySave(target.id, shown);
    }, queryKeys.accountTransactionScope(accountId));
  }

  function archiveOrRestore(restore: boolean) {
    if (!summary || !online || archiveWrite.isPending) return;
    void runWrite(async () => {
      const cached = client.getQueryData<{ version: number }>(queryKeys.account(accountId));
      await retryVersionRequired(
        (version) => archiveWrite.mutateAsync({ version, restore }),
        cached?.version ?? summary.version,
        reloadAccountVersion,
      );
      setDialog(null);
    }, queryKeys.account(accountId));
  }

  async function reviewOpeningDateConflict() {
    if (openingDateReloadInFlight.current) return;
    openingDateReloadInFlight.current = true;
    conflict.dismiss();
    setReloadingConflict(true);
    setOpeningDateReloadError(null);
    try {
      const latest = await reloadAccountVersion();
      setOpeningDateDraft((draft) => (draft ? { ...draft, account: latest } : null));
      await invalidateFinancialData(client);
    } catch (error) {
      if (isNotFound(error)) {
        showToast('account.notFound');
        onBack();
      } else {
        setOpeningDateReloadError({ error });
      }
    } finally {
      openingDateReloadInFlight.current = false;
      setReloadingConflict(false);
    }
  }

  function changeOpeningDate(openedAt: string | null) {
    setOpeningDateDraft((draft) => (draft ? { ...draft, openedAt } : null));
    setWriteError(null);
  }

  function saveOpeningDate() {
    if (
      !openingDateDraft ||
      !online ||
      openingDateWrite.isPending ||
      openingDateNeedsReload ||
      openingDateInFuture
    )
      return;
    void runWrite(async () => {
      await openingDateWrite.mutateAsync(openingDateDraft);
      setDialog(null);
      setOpeningDateDraft(null);
    }, queryKeys.account(accountId));
  }

  if (account.isError && isAccessFailure(account.error)) {
    // This guard takes precedence even when TanStack Query retains a cached account summary.
    // Never present cached identity, balances, positions or transactions after 401/403/404.
    return isNotFound(account.error) ? (
      <EmptyState
        title={intl.formatMessage({ id: 'account.notFound' })}
        actionLabel={intl.formatMessage({ id: 'account.back' })}
        onAction={onBack}
      />
    ) : (
      <ErrorBanner error={account.error} />
    );
  }
  if (account.isPending && !summary) return <LoadingSkeleton count={8} height={24} />;
  if (account.isError && !summary) {
    // Render only the bounded S1/S2 list item already owned by this signed-in session.
    // No synthetic account capabilities or reconciliation are invented for the fallback.
    if (cachedValue && isRecoverableAccountFailure(account.error)) {
      return (
        <View testID="account-cached-fallback" style={{ gap: Spacing.three }}>
          <Pressable
            accessibilityRole="button"
            onPress={onBack}
            style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}
          >
            <Text style={{ color: theme.brandLink }}>
              {intl.formatMessage({ id: 'account.back' })}
            </Text>
          </Pressable>
          <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />
          <Text
            accessibilityRole="header"
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
          >
            {cachedValue.name}
          </Text>
          <Amount
            value={cachedValue.balance}
            currency={cachedValue.currency}
            valueKnown={cachedValue.valueKnown}
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
          />
          <ValueStatusBadge
            status={cachedValue.valueStatus}
            valueSourceDate={cachedValue.valueSourceDate}
            interactive={false}
          />
          <Text style={{ color: theme.textSecondary }}>
            {intl.formatMessage(
              { id: 'account.asOf' },
              { date: formatDate(cachedValue.asOf, intl.locale) },
            )}
          </Text>
        </View>
      );
    }
    return <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />;
  }
  if (!summary) return null;

  const valueQueries = archived ? [archivedAccounts, activeAccounts] : [activeAccounts];
  const firstPageFailed = transactions.isError && !transactions.isFetchNextPageError;
  // Refresh also runs the enabled reads for the inactive depot tab.
  const sharedFailures = [
    account,
    ...valueQueries,
    positions,
    count,
    categories,
    ...(firstPageFailed ? [transactions] : []),
  ].filter((query) => query.isEnabled && isSharedReadFailure(query.error));
  const renewalFailure = sharedFailures.find(
    (query) => query.error instanceof SessionRefreshUnavailableError,
  );
  const sharedError =
    sharedFailures.length > 1 ? (renewalFailure ?? sharedFailures[0]).error : null;
  const inlineError = (error: unknown) =>
    sharedError && isSharedReadFailure(error) ? null : error;
  const valueError = valueQueries.map((query) => inlineError(query.error)).find(Boolean);
  const valuePending = (archived ? archivedAccounts : activeAccounts).isPending;
  const valueFeedback =
    valueError || (!value && !valuePending && valueQueries.every((query) => !query.isError)) ? (
      <ErrorBanner
        error={valueError}
        onRetry={() => {
          const failed = valueQueries.filter((query) => inlineError(query.error));
          for (const query of failed.length
            ? failed
            : [archived ? archivedAccounts : activeAccounts]) {
            void query.refetch({ cancelRefetch: false });
          }
        }}
      />
    ) : null;

  const context = {
    categories: categories.data ?? [],
    reversalDates,
    accountCurrency: summary.nativeCurrency,
    archived,
    includeParent: false,
  };

  function openCategoryPicker(target: Transaction) {
    setPendingCategoryId(null);
    setCategoryTarget(target);
  }

  function afterRowActions(action: () => void) {
    setActionRowId(null);
    // Let iOS finish dismissing its modal and web return focus to the opener.
    if (Platform.OS === 'ios' || Platform.OS === 'web') deferredRowAction.current = action;
    else action();
  }

  const actionView = actionRow ? presentTransaction(actionRow, context, intl) : null;
  const purpose = actionRow ? transactionPurpose(actionRow) : null;

  // The header and controls scroll WITH the transactions on native. This keeps one
  // virtualized scroll owner (SectionList) rather than nesting a list in ShellScreen's ScrollView.
  const accountHeader = (
    <View style={{ gap: Spacing.three }}>
      <Pressable
        accessibilityRole="button"
        onPress={onBack}
        style={{ minHeight: 44, alignSelf: 'flex-start', justifyContent: 'center' }}
      >
        <Text style={{ color: theme.brandLink }}>
          <Text aria-hidden>‹ </Text>
          {intl.formatMessage({ id: 'account.back' })}
        </Text>
      </Pressable>
      {!online ? (
        <OfflineBanner
          lastUpdated={
            account.dataUpdatedAt ? new Date(account.dataUpdatedAt).toISOString() : undefined
          }
        />
      ) : null}
      {sharedError ? (
        <ErrorBanner
          error={sharedError}
          onRetry={() => {
            for (const query of sharedFailures) void query.refetch({ cancelRefetch: false });
          }}
        />
      ) : null}
      {inlineError(account.error) ? (
        <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />
      ) : null}
      {writeError && dialog !== 'openingDate' ? (
        <ErrorBanner error={writeError} onRetry={() => retryWrite.current?.()} />
      ) : null}
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: Spacing.two,
        }}
      >
        <View style={{ flex: 1 }}>
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {intl.formatMessage(
              { id: maskedIdentifier ? 'account.contextMasked' : 'account.context' },
              {
                portfolio: value?.portfolioName ?? '–',
                type: accountTypeLabel(summary.accountType, intl),
                identifier: maskedIdentifier ?? '',
              },
            )}
          </Text>
          <Text
            ref={headingRef}
            accessibilityRole="header"
            tabIndex={-1}
            style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}
          >
            {summary.name}
          </Text>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={intl.formatMessage({ id: 'account.actions' })}
          onPress={() => setDialog('actions')}
          style={{ minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' }}
        >
          <Text style={{ color: theme.textPrimary, fontSize: 24 }}>⋯</Text>
        </Pressable>
      </View>
      <AccountHeaderCard
        summary={summary}
        value={value}
        valuePending={valuePending}
        valueFeedback={valueFeedback}
        onExplainValue={() => setDialog('value')}
      />
      {summary.openedAt ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {intl.formatMessage(
            { id: 'account.openedAt.display' },
            { date: formatDate(summary.openedAt, intl.locale) },
          )}
        </Text>
      ) : null}
      {pocket.currentLine}
      {value ? valueFeedback : null}
      {archived ? (
        <ArchivedBanner
          archivedAt={summary.archivedAt}
          disabled={!online || archiveWrite.isPending}
          onRestore={() => archiveOrRestore(true)}
        />
      ) : null}
      {!liabilityInAside ? (
        <LiabilityDetail account={summary} onTransaction={onRepaymentTransaction} />
      ) : null}
      {summary.holdsPositions ? (
        <ChipGroup
          options={[
            { value: 'positions', label: intl.formatMessage({ id: 'account.positions' }) },
            { value: 'activity', label: intl.formatMessage({ id: 'account.activity' }) },
          ]}
          selected={[tab]}
          onSelect={(next) => setTab(next === 'positions' ? 'positions' : 'activity')}
        />
      ) : null}
      {showingPositions ? (
        positions.isPending ? (
          <LoadingSkeleton count={3} />
        ) : inlineError(positions.error) ? (
          <ErrorBanner error={positions.error} onRetry={() => void positions.refetch()} />
        ) : positions.data?.positions.length ? (
          <PositionView
            positions={positions.data.positions}
            currency={positions.data.currency}
            accountName={summary.name}
          />
        ) : positions.data ? (
          <EmptyState title={intl.formatMessage({ id: 'account.noPositions' })} />
        ) : null
      ) : !hasLedger ? (
        <EmptyState
          title={valueBasisExplanation(value?.valueBasis, intl)}
          description={
            value
              ? intl.formatMessage(
                  { id: 'account.asOf' },
                  { date: formatDate(value.asOf, intl.locale) },
                )
              : undefined
          }
        />
      ) : (
        <>
          <ChipGroup
            label={intl.formatMessage({ id: 'account.filter' })}
            options={[
              { value: 'all', label: intl.formatMessage({ id: 'account.all' }) },
              ...(countValue > 0
                ? [
                    {
                      value: 'uncategorized',
                      label: intl.formatMessage(
                        { id: 'account.uncategorized' },
                        { count: countValue },
                      ),
                    },
                  ]
                : []),
            ]}
            selected={[uncategorized ? 'uncategorized' : 'all']}
            onSelect={(next) => changeActivityView(next === 'uncategorized', sort)}
          />
          {countValue > 0 ? (
            <Pressable
              testID="account-needs-category"
              accessibilityRole="button"
              onPress={() => changeActivityView(true, sort)}
              style={{
                padding: Spacing.two,
                backgroundColor: theme.statusWarningSubtle,
                minHeight: 44,
                gap: Spacing.one,
              }}
            >
              <Text style={{ color: theme.statusWarning }}>
                ⚠ {intl.formatMessage({ id: 'account.needsCategory' }, { count: countValue })}
              </Text>
              <Text style={{ color: theme.textSecondary }}>
                {intl.formatMessage({ id: 'account.needsCategoryNote' })}
              </Text>
              <Text style={{ color: theme.brandLink }}>
                {intl.formatMessage({ id: 'account.review' })}
              </Text>
            </Pressable>
          ) : null}
          <Pressable
            accessibilityRole="button"
            accessibilityHint={intl.formatMessage({ id: 'account.sort' })}
            onPress={() => setDialog('sort')}
            style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}
          >
            <Text style={{ color: theme.brandLink }}>
              {intl.formatMessage({ id: sortMessageId(sort) })} ⌄
            </Text>
          </Pressable>
          {inlineError(count.error) ? (
            <ErrorBanner error={count.error} onRetry={() => void count.refetch()} />
          ) : null}
          {inlineError(categories.error) ? (
            <ErrorBanner error={categories.error} onRetry={() => void categories.refetch()} />
          ) : null}
          {transactions.isPending && !transactions.data ? <LoadingSkeleton count={5} /> : null}
          {firstPageFailed && inlineError(transactions.error) ? (
            // Keep previously loaded rows visible, but do not silently hide a failed refresh.
            <ErrorBanner error={transactions.error} onRetry={() => void transactions.refetch()} />
          ) : null}
          {records.length === 0 && transactions.isSuccess ? (
            uncategorized ? (
              <EmptyState
                title={intl.formatMessage({ id: 'account.noUncategorized' })}
                actionLabel={intl.formatMessage({ id: 'account.showAll' })}
                onAction={() => changeActivityView(false, sort)}
              />
            ) : (
              <EmptyState
                title={intl.formatMessage({ id: 'account.noTransactions' })}
                description={intl.formatMessage({ id: 'account.importHint' })}
                actionLabel={
                  archived || !online ? undefined : intl.formatMessage({ id: 'account.import' })
                }
                onAction={archived || !online ? undefined : onImport}
              />
            )
          ) : null}
        </>
      )}
    </View>
  );
  // A cached first page can remain visible after a paused or failed refresh.
  const canFetchNextPage =
    transactions.hasNextPage && transactions.fetchStatus === 'idle' && !transactions.isRefetchError;
  const fetchNextPage = () => {
    if (canFetchNextPage) void transactions.fetchNextPage({ cancelRefetch: false });
  };
  const onEndReached =
    showingTransactions && canFetchNextPage && !transactions.isFetchNextPageError
      ? fetchNextPage
      : undefined;
  const accountFooter = showingTransactions ? (
    <View style={{ gap: Spacing.three }}>
      {records.length ? (
        <Text accessibilityLiveRegion="polite" style={{ color: theme.textSecondary }}>
          {intl.formatMessage({ id: 'account.showing' }, { shown: records.length, total })}
        </Text>
      ) : null}
      {transactions.isFetchNextPageError ? (
        <ErrorBanner error={transactions.error} onRetry={fetchNextPage} />
      ) : null}
      {transactions.hasNextPage && !transactions.isFetchNextPageError ? (
        <ActionButton
          disabled={!canFetchNextPage}
          label={intl.formatMessage({ id: 'account.loadMore' })}
          onPress={() => {
            if (canFetchNextPage) rowFocus.beforeLoadMore(recordIds);
            fetchNextPage();
          }}
        />
      ) : null}
      {transactions.isFetchingNextPage ? <ActivityIndicator /> : null}
    </View>
  ) : null;

  return (
    <View testID="account-detail" style={{ flex: 1, gap: Spacing.three }}>
      {Platform.OS === 'web' ? (
        <>
          {accountHeader}
          {showingTransactions ? (
            <TransactionResults
              transactions={readableTransactions ? records : []}
              selectedTransactionId={selectedTransactionId}
              context={context}
              byDate={sort.startsWith('bookingDate')}
              onOpen={onTransaction}
              onCategory={openCategoryPicker}
              onActions={(target) => setActionRowId(target.id)}
              onEndReached={onEndReached}
            />
          ) : null}
          {accountFooter}
        </>
      ) : (
        <TransactionResults
          transactions={readableTransactions ? records : []}
          selectedTransactionId={selectedTransactionId}
          context={context}
          byDate={sort.startsWith('bookingDate')}
          onOpen={onTransaction}
          onCategory={openCategoryPicker}
          onActions={(target) => setActionRowId(target.id)}
          onEndReached={onEndReached}
          listHeader={accountHeader}
          listFooter={accountFooter}
          refreshing={refreshing}
          onRefresh={onRefresh}
        />
      )}
      <ActionsDialog
        visible={dialog === 'actions'}
        archived={archived}
        online={online}
        onClose={() => setDialog(null)}
        onImport={() => {
          if (!online || archived) return;
          setDialog(null);
          onImport();
        }}
        onArchive={() => setDialog('archive')}
        onEditOpeningDate={() => {
          setWriteError(null);
          openingDateWrite.reset();
          setOpeningDateDraft({ account: summary, openedAt: summary.openedAt ?? null });
          setDialog('openingDate');
        }}
        pocketAction={pocket.trigger(() => setDialog(null))}
      />
      {pocket.dialog}
      <SortDialog
        visible={dialog === 'sort'}
        sort={sort}
        onClose={() => setDialog(null)}
        onSort={(next) => {
          changeActivityView(uncategorized, next);
          setDialog(null);
        }}
      />
      <TransactionActionsDialog
        visible={!!actionRow}
        onClose={() => setActionRowId(null)}
        onDismiss={() => {
          const action = deferredRowAction.current;
          deferredRowAction.current = null;
          if (readableTransactions) action?.();
        }}
        onDetails={
          actionRow && onTransaction
            ? () => afterRowActions(() => onTransaction(actionRow.id))
            : undefined
        }
        onCategory={
          actionRow && actionView?.canCategorize
            ? () => afterRowActions(() => openCategoryPicker(actionRow))
            : undefined
        }
        categoryDisabled={locked}
        onCopy={
          purpose
            ? () => {
                setActionRowId(null);
                void copyTransactionPurpose(purpose);
              }
            : undefined
        }
      />
      <CategoryPickerDialog
        visible={categoryTarget !== null}
        categories={categories.data ?? []}
        disabled={locked}
        selectedCategoryId={pendingCategoryId}
        onClose={() => {
          setPendingCategoryId(null);
          setCategoryTarget(null);
        }}
        onPick={changeCategory}
        onDismiss={rowFocus.dialogDismissed}
      />
      <ArchiveDialog
        visible={dialog === 'archive'}
        name={summary.name}
        disabled={!online || archiveWrite.isPending}
        onClose={() => setDialog(null)}
        onConfirm={() => archiveOrRestore(false)}
      />
      <ValueExplanationSheet
        visible={dialog === 'value'}
        summary={summary}
        value={value}
        onClose={() => setDialog(null)}
      />
      {openingDateDraft && (
        <Dialog
          visible={dialog === 'openingDate' && !conflict.conflicted}
          title={intl.formatMessage({ id: 'account.openedAt.edit' })}
          closeDisabled={openingDateWrite.isPending || reloadingConflict}
          onClose={() => {
            setDialog(null);
            setOpeningDateDraft(null);
            setWriteError(null);
            retryWrite.current = null;
            setOpeningDateReloadError(null);
          }}
        >
          <DateField
            label={intl.formatMessage({ id: 'account.openedAt.optional' })}
            value={openingDateDraft.openedAt}
            onChange={changeOpeningDate}
            onClear={() => changeOpeningDate(null)}
            hint={intl.formatMessage({ id: 'account.openedAt.hint' })}
            maximumDate={today}
            disabled={openingDateWrite.isPending || reloadingConflict}
            error={
              openingDateInvalid
                ? intl.formatMessage({ id: 'account.openedAt.invalid' })
                : undefined
            }
          />
          {openingDateReloadError ? (
            <ErrorBanner
              error={openingDateReloadError.error}
              onRetry={online ? () => void reviewOpeningDateConflict() : undefined}
            />
          ) : null}
          {writeError && !openingDateRejected ? <ErrorBanner error={writeError} /> : null}
          {!online ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {intl.formatMessage({ id: 'addFlow.offline' })}
            </Text>
          ) : null}
          <ActionButton
            label={intl.formatMessage({ id: 'account.openedAt.save' })}
            disabled={
              !online ||
              openingDateWrite.isPending ||
              openingDateNeedsReload ||
              openingDateDraft.openedAt === (openingDateDraft.account.openedAt ?? null) ||
              openingDateInFuture
            }
            onPress={saveOpeningDate}
          />
        </Dialog>
      )}
      <ConflictDialog
        visible={conflict.conflicted}
        onClose={() => {
          conflict.dismiss();
          setCategoryTarget(null);
          setPendingCategoryId(null);
          setReloadingConflict(false);
          if (dialog === 'openingDate') {
            setDialog(null);
            setOpeningDateDraft(null);
          }
        }}
        onDiscard={() => {
          conflict.dismiss();
          setCategoryTarget(null);
          setPendingCategoryId(null);
          setReloadingConflict(false);
          setDialog(null);
          setOpeningDateDraft(null);
        }}
        onReview={() => {
          if (dialog === 'openingDate') {
            void reviewOpeningDateConflict();
            return;
          }
          conflict.dismiss();
          setReloadingConflict(true);
          void (async () => {
            try {
              if (categoryTarget) await reloadTransactionVersion(categoryTarget.id);
              else await reloadAccountVersion();
              await invalidateFinancialData(client);
            } catch (error) {
              if (isNotFound(error)) {
                showToast('account.notFound');
                onBack();
              } else {
                setWriteError(error);
              }
            } finally {
              setReloadingConflict(false);
            }
          })();
        }}
      />
    </View>
  );
}
