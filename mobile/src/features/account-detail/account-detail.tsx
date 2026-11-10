import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Platform, Pressable, Text, View } from 'react-native';

import { ApiError, NetworkError } from '@/api/client';
import { typedApi } from '@/api/typed-api';
import { problemReaction } from '@/api/problems';
import { retryVersionRequired, useConflict } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys, queryRoots } from '@/api/queries/keys';
import {
  useAccount,
  useAccountArchiveAction,
  useAccountPositions,
  useAccountTransactions,
  useAccounts,
  useArchivedAccounts,
  useCategories,
  useChangeTransactionCategory,
  useUncategorizedCount,
  type TransactionSort,
} from '@/api/queries/resources';
import {
  ActionButton,
  Amount,
  ChipGroup,
  ConflictDialog,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  OfflineBanner,
  ValueStatusBadge,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate } from '@/format/date';
import { PositionView } from '@/features/portfolio-view/position-view';
import { showToast } from '@/features/shell/toast';
import { useOnline } from '@/hooks/use-online';
import { useTheme } from '@/hooks/use-theme';

import {
  ActionsDialog,
  ArchiveDialog,
  CategoryPickerDialog,
  SortDialog,
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
  type AccountListItem,
  type Transaction,
} from './model';
import { TransactionResults } from './transaction-results';

export type AccountDetailProps = {
  accountId: string;
  onBack: () => void;
  onTransaction?: (id: string) => void;
  /** The selected transaction in a desktop S4 panel, if any. */
  selectedTransactionId?: string | null;
  onImport: () => void;
  /** Native pull-to-refresh shares the same single SectionList scroll owner. */
  refreshing?: boolean;
  onRefresh?: () => void;
};

type OpenDialog = 'actions' | 'sort' | 'archive' | 'value' | null;

const isNotFound = (error: unknown) =>
  error instanceof ApiError &&
  (error.status === 404 || problemReaction(error).sentCode === 'NOT_FOUND');

/** An account error must not reveal a previously cached financial value after access was denied. */
const isAccessFailure = (error: unknown) =>
  error instanceof ApiError &&
  (error.status === 401 ||
    error.status === 403 ||
    isNotFound(error) ||
    problemReaction(error).sentCode === 'UNAUTHENTICATED' ||
    problemReaction(error).sentCode === 'FORBIDDEN');

/** A stale S1/S2 value is only safe as a fallback while the server is unreachable or failing. */
const isRecoverableAccountFailure = (error: unknown) =>
  error instanceof NetworkError ||
  (error instanceof ApiError && error.status >= 500 && error.status < 600);

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
  selectedTransactionId,
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
  const [tab, setTab] = useState<'positions' | 'activity'>('activity');
  const [categoryTarget, setCategoryTarget] = useState<Transaction | null>(null);
  const [pendingCategoryId, setPendingCategoryId] = useState<string | null>(null);
  const [reloadingConflict, setReloadingConflict] = useState(false);
  const writeInFlight = useRef(false);
  const [dialog, setDialog] = useState<OpenDialog>(null);
  const [writeError, setWriteError] = useState<unknown>(null);
  const retryWrite = useRef<(() => void) | null>(null);
  const conflict = useConflict();

  const account = useAccount(accountId);
  const summary = account.data;
  const archived = summary?.status === 'ARCHIVED';
  const hasLedger = !!summary?.hasTransactions;
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
  const records = uniqueTransactions(transactions.data?.pages ?? []);
  const total = transactions.data?.pages[0]?.page.totalElements ?? 0;
  const countValue = count.data ?? 0;
  const locked =
    !online || archived || reloadingConflict || categoryWrite.isPending || archiveWrite.isPending;

  // Spec S3: the chip is hidden at 0 and the filter falls back to All (server count, page 0).
  // Adjusted while rendering, so the stale filter never reaches a request or a frame.
  if (uncategorized && count.data === 0) setUncategorized(false);

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

  const refresh = () => void invalidateFinancialData(client);

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

  const context = {
    categories: categories.data ?? [],
    loaded: records,
    accountCurrency: summary.nativeCurrency,
    archived,
    includeParent: false,
  };

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
      {account.isError ? (
        <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />
      ) : null}
      {writeError ? (
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
              { id: 'account.context' },
              {
                portfolio: value?.portfolioName ?? '–',
                type: accountTypeLabel(summary.accountType, intl),
                currency: summary.nativeCurrency,
              },
            )}
          </Text>
          <Text
            accessibilityRole="header"
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
        valuePending={activeAccounts.isPending || (archived && archivedAccounts.isPending)}
        valueError={activeAccounts.error ?? archivedAccounts.error}
        onRetryValue={refresh}
        onExplainValue={() => setDialog('value')}
      />
      {value && (activeAccounts.isError || archivedAccounts.isError) ? (
        <ErrorBanner error={activeAccounts.error ?? archivedAccounts.error} onRetry={refresh} />
      ) : null}
      {archived ? (
        <ArchivedBanner
          archivedAt={summary.archivedAt}
          disabled={!online || archiveWrite.isPending}
          onRestore={() => archiveOrRestore(true)}
        />
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
      {summary.holdsPositions && tab === 'positions' ? (
        positions.isPending ? (
          <LoadingSkeleton count={3} />
        ) : positions.isError ? (
          <ErrorBanner error={positions.error} onRetry={() => void positions.refetch()} />
        ) : positions.data?.positions.length ? (
          <PositionView
            positions={positions.data.positions}
            currency={positions.data.currency}
            accountName={summary.name}
          />
        ) : (
          <EmptyState title={intl.formatMessage({ id: 'account.noPositions' })} />
        )
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
            onSelect={(next) => setUncategorized(next === 'uncategorized')}
          />
          {countValue > 0 ? (
            <Pressable
              testID="account-needs-category"
              accessibilityRole="button"
              onPress={() => setUncategorized(true)}
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
          {count.isError ? (
            <ErrorBanner error={count.error} onRetry={() => void count.refetch()} />
          ) : null}
          {categories.isError ? (
            <ErrorBanner error={categories.error} onRetry={() => void categories.refetch()} />
          ) : null}
          {transactions.isPending && !transactions.data ? <LoadingSkeleton count={5} /> : null}
          {transactions.isError ? (
            // Keep previously loaded rows visible, but do not silently hide a failed refresh.
            <ErrorBanner error={transactions.error} onRetry={() => void transactions.refetch()} />
          ) : null}
          {records.length === 0 && transactions.isSuccess ? (
            uncategorized ? (
              <EmptyState
                title={intl.formatMessage({ id: 'account.noUncategorized' })}
                actionLabel={intl.formatMessage({ id: 'account.showAll' })}
                onAction={() => setUncategorized(false)}
              />
            ) : (
              <EmptyState
                title={intl.formatMessage({ id: 'account.noTransactions' })}
                description={intl.formatMessage({ id: 'account.importHint' })}
                actionLabel={archived || !online ? undefined : intl.formatMessage({ id: 'account.import' })}
                onAction={archived || !online ? undefined : onImport}
              />
            )
          ) : null}
        </>
      )}
    </View>
  );
  const showingTransactions = hasLedger && !(summary.holdsPositions && tab === 'positions');
  const accountFooter = showingTransactions ? (
    <View style={{ gap: Spacing.three }}>
      {records.length ? (
        <Text accessibilityLiveRegion="polite" style={{ color: theme.textSecondary }}>
          {intl.formatMessage({ id: 'account.showing' }, { shown: records.length, total })}
        </Text>
      ) : null}
      {transactions.isFetchNextPageError ? (
        <ErrorBanner error={transactions.error} onRetry={() => void transactions.fetchNextPage()} />
      ) : null}
      {transactions.hasNextPage && !transactions.isFetchNextPageError ? (
        <ActionButton
          disabled={transactions.isFetchingNextPage}
          label={intl.formatMessage({ id: 'account.loadMore' })}
          onPress={() => void transactions.fetchNextPage()}
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
              transactions={records}
              selectedTransactionId={selectedTransactionId}
              context={context}
              byDate={sort.startsWith('bookingDate')}
              onOpen={onTransaction}
              onCategory={(target) => {
                setPendingCategoryId(null);
                setCategoryTarget(target);
              }}
              onEndReached={
                transactions.hasNextPage && !transactions.isFetchNextPageError
                  ? () => {
                      if (!transactions.isFetchingNextPage) void transactions.fetchNextPage();
                    }
                  : undefined
              }
            />
          ) : null}
          {accountFooter}
        </>
      ) : (
        <TransactionResults
          transactions={showingTransactions ? records : []}
          selectedTransactionId={selectedTransactionId}
          context={context}
          byDate={sort.startsWith('bookingDate')}
          onOpen={onTransaction}
          onCategory={(target) => {
            setPendingCategoryId(null);
            setCategoryTarget(target);
          }}
          onEndReached={
            showingTransactions && transactions.hasNextPage && !transactions.isFetchNextPageError
              ? () => {
                  if (!transactions.isFetchingNextPage) void transactions.fetchNextPage();
                }
              : undefined
          }
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
      />
      <SortDialog
        visible={dialog === 'sort'}
        sort={sort}
        onClose={() => setDialog(null)}
        onSort={(next) => {
          setSort(next);
          setDialog(null);
        }}
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
      <ConflictDialog
        visible={conflict.conflicted}
        onClose={() => {
          conflict.dismiss();
          setCategoryTarget(null);
          setPendingCategoryId(null);
          setReloadingConflict(false);
        }}
        onDiscard={() => {
          conflict.dismiss();
          setCategoryTarget(null);
          setPendingCategoryId(null);
          setReloadingConflict(false);
          setDialog(null);
        }}
        onReview={() => {
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
