import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';
import { useConflict } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';
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
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
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
  asOfLine,
  hasUnknownCategory,
  uniqueTransactions,
  valueSource,
  valueSourceLabel,
  type Transaction,
} from './model';
import { TransactionResults } from './transaction-results';

export type AccountDetailProps = {
  accountId: string;
  onBack: () => void;
  onTransaction: (id: string) => void;
  onImport: () => void;
};

type OpenDialog = 'actions' | 'sort' | 'archive' | 'value' | null;

const isNotFound = (error: unknown) =>
  error instanceof ApiError &&
  (error.status === 404 || problemReaction(error).sentCode === 'NOT_FOUND');

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

export function AccountDetail({ accountId, onBack, onTransaction, onImport }: AccountDetailProps) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useOnline();
  const client = useQueryClient();
  const [uncategorized, setUncategorized] = useState(false);
  const [sort, setSort] = useState<TransactionSort>('bookingDate,desc');
  const [tab, setTab] = useState<'positions' | 'activity'>('activity');
  const [categoryTarget, setCategoryTarget] = useState<Transaction | null>(null);
  const [dialog, setDialog] = useState<OpenDialog>(null);
  const [writeError, setWriteError] = useState<unknown>(null);
  const retryWrite = useRef<(() => void) | null>(null);
  const conflict = useConflict();

  const account = useAccount(accountId);
  const summary = account.data;
  const archived = summary?.status === 'ARCHIVED';
  const hasLedger = !!summary?.hasTransactions;
  const activeAccounts = useAccounts(summary?.portfolioId);
  const archivedAccounts = useArchivedAccounts(summary?.portfolioId ?? '', archived);
  const value = accountValuation(summary, activeAccounts.data, archivedAccounts.data);
  const categories = useCategories();
  const count = useUncategorizedCount(accountId, hasLedger);
  const transactions = useAccountTransactions(accountId, uncategorized, sort, hasLedger);
  const positions = useAccountPositions(accountId, !!summary?.holdsPositions);
  const categoryWrite = useChangeTransactionCategory(accountId);
  const archiveWrite = useAccountArchiveAction(accountId);
  const records = uniqueTransactions(transactions.data?.pages ?? []);
  const total = transactions.data?.pages[0]?.page.totalElements ?? 0;
  const countValue = count.data ?? 0;
  const locked = !online || archived || categoryWrite.isPending || archiveWrite.isPending;

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

  async function runWrite(write: () => Promise<unknown>, conflictKey: QueryKey) {
    setWriteError(null);
    retryWrite.current = null;
    try {
      await write();
    } catch (error) {
      if (await conflict.handle(error, conflictKey)) return;
      if (isNotFound(error)) {
        showToast('account.notFound');
        onBack();
        return;
      }
      retryWrite.current = () => void runWrite(write, conflictKey);
      setWriteError(error);
    }
  }

  function changeCategory(categoryId: string) {
    const target = categoryTarget;
    if (!target || locked) return;
    void runWrite(async () => {
      await categoryWrite.mutateAsync({
        transactionId: target.id,
        categoryId,
        version: target.version,
      });
      setCategoryTarget(null);
    }, queryKeys.accountTransactionScope(accountId));
  }

  function archiveOrRestore(restore: boolean) {
    if (!summary || !online || archiveWrite.isPending) return;
    const version = summary.version;
    void runWrite(async () => {
      await archiveWrite.mutateAsync({ version, restore });
      setDialog(null);
    }, queryKeys.account(accountId));
  }

  if (account.isPending && !summary) return <LoadingSkeleton count={8} height={24} />;
  if (account.isError && !summary) {
    return isNotFound(account.error) ? (
      <EmptyState
        title={intl.formatMessage({ id: 'account.notFound' })}
        actionLabel={intl.formatMessage({ id: 'account.back' })}
        onAction={onBack}
      />
    ) : (
      <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />
    );
  }
  if (!summary) return null;

  const context = {
    categories: categories.data ?? [],
    loaded: records,
    accountCurrency: summary.nativeCurrency,
    archived,
    includeParent: false,
  };

  return (
    <View testID="account-detail" style={{ flex: 1, gap: Spacing.three }}>
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
          positions.data.positions.map((position) => (
            <View
              key={position.securityId}
              style={{
                padding: Spacing.two,
                borderBottomWidth: 1,
                borderBottomColor: theme.borderDefault,
              }}
            >
              <Text style={{ color: theme.textPrimary }}>{position.displayName}</Text>
              {position.isin ? (
                <Text style={{ color: theme.textSecondary }}>{position.isin}</Text>
              ) : null}
              <Amount value={position.valueInAccountCurrency} currency={positions.data.currency} />
            </View>
          ))
        ) : (
          <EmptyState title={intl.formatMessage({ id: 'account.noPositions' })} />
        )
      ) : !hasLedger ? (
        <EmptyState
          title={intl.formatMessage(
            { id: 'account.valueFrom' },
            { basis: valueSourceLabel(valueSource(summary), intl) },
          )}
          description={value ? asOfLine(valueSource(summary), value, intl) : undefined}
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
          {transactions.isError && !transactions.data ? (
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
                actionLabel={archived ? undefined : intl.formatMessage({ id: 'account.import' })}
                onAction={archived ? undefined : onImport}
              />
            )
          ) : null}
          <TransactionResults
            transactions={records}
            context={context}
            byDate={sort.startsWith('bookingDate')}
            onOpen={onTransaction}
            onCategory={setCategoryTarget}
            onEndReached={
              transactions.hasNextPage && !transactions.isFetchNextPageError
                ? () => {
                    if (!transactions.isFetchingNextPage) void transactions.fetchNextPage();
                  }
                : undefined
            }
          />
          {records.length ? (
            <Text accessibilityLiveRegion="polite" style={{ color: theme.textSecondary }}>
              {intl.formatMessage({ id: 'account.showing' }, { shown: records.length, total })}
            </Text>
          ) : null}
          {transactions.isFetchNextPageError ? (
            <ErrorBanner
              error={transactions.error}
              onRetry={() => void transactions.fetchNextPage()}
            />
          ) : null}
          {transactions.hasNextPage && !transactions.isFetchNextPageError ? (
            <ActionButton
              disabled={transactions.isFetchingNextPage}
              label={intl.formatMessage({ id: 'account.loadMore' })}
              onPress={() => void transactions.fetchNextPage()}
            />
          ) : null}
          {transactions.isFetchingNextPage ? <ActivityIndicator /> : null}
        </>
      )}
      <ActionsDialog
        visible={dialog === 'actions'}
        archived={archived}
        online={online}
        onClose={() => setDialog(null)}
        onImport={() => {
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
        onClose={() => setCategoryTarget(null)}
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
        onClose={conflict.dismiss}
        onDiscard={() => {
          conflict.dismiss();
          setCategoryTarget(null);
          setDialog(null);
        }}
        onReview={() => {
          conflict.dismiss();
          refresh();
        }}
      />
    </View>
  );
}
