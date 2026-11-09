import { useState, useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import {
  useAccount, useAccounts, useAccountArchiveAction, useAccountPositions,
  useAccountTransactions, useArchivedAccounts, useCategories,
  useChangeTransactionCategory, useUncategorizedCount,
  type TransactionSort,
} from '@/api/queries/resources';
import { useConflict } from '@/api/queries/conflict';
import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';
import { ActionButton, Amount, ChipGroup, ConflictDialog, Dialog, EmptyState, ErrorBanner, LoadingSkeleton, OfflineBanner, ValueStatusBadge } from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate, formatRelativeDay } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { accountValuation, categoryName, groupedTransactions, uniqueTransactions, type Transaction } from './model';
import { TransactionLine } from './transaction-line';

export type AccountDetailProps = {
  accountId: string;
  onBack: () => void;
  onTransaction: (id: string) => void;
  onImport: () => void;
};

const SORTS: TransactionSort[] = ['bookingDate,desc', 'bookingDate,asc', 'amount,desc', 'amount,asc'];
const SORT_KEYS = ['account.newest', 'account.oldest', 'account.largest', 'account.smallest'];

export function AccountDetail({ accountId, onBack, onTransaction, onImport }: AccountDetailProps) {
  const intl = useIntl();
  const theme = useTheme();
  const online = useSyncExternalStore(
    (subscribe) => onlineManager.subscribe(subscribe),
    () => onlineManager.isOnline(),
    () => true,
  );
  const [uncategorized, setUncategorized] = useState(false);
  const [sort, setSort] = useState<TransactionSort>('bookingDate,desc');
  const [tab, setTab] = useState('activity');
  const [categoryTarget, setCategoryTarget] = useState<Transaction | null>(null);
  const [archiveConfirmation, setArchiveConfirmation] = useState(false);
  const [showActions, setShowActions] = useState(false);
  const [showSort, setShowSort] = useState(false);
  const [showValue, setShowValue] = useState(false);
  const [writeError, setWriteError] = useState<unknown>(null);
  const conflict = useConflict();
  const account = useAccount(accountId);
  const summary = account.data;
  const archived = summary?.status === 'ARCHIVED';
  const activeAccounts = useAccounts(summary?.portfolioId);
  const archivedAccounts = useArchivedAccounts(summary?.portfolioId ?? '', archived);
  const value = accountValuation(summary, activeAccounts.data, archivedAccounts.data);
  const categories = useCategories();
  const transactions = useAccountTransactions(accountId, uncategorized, sort, !!summary?.hasTransactions);
  const count = useUncategorizedCount(accountId, !!summary?.hasTransactions);
  const positions = useAccountPositions(accountId, !!summary?.holdsPositions);
  const categoryWrite = useChangeTransactionCategory(accountId);
  const archiveWrite = useAccountArchiveAction(accountId);
  const records = uniqueTransactions(transactions.data?.pages ?? []);
  const total = transactions.data?.pages[0]?.page.totalElements ?? 0;
  const countValue = count.data ?? 0;
  const refreshing = account.isRefetching || transactions.isRefetching;
  const locked = !online || archived || categoryWrite.isPending || archiveWrite.isPending;

  const refresh = () => {
    void Promise.allSettled([
      account.refetch(), activeAccounts.refetch(),
      ...(archived ? [archivedAccounts.refetch()] : []),
      ...(summary?.hasTransactions ? [transactions.refetch(), count.refetch(), categories.refetch()] : []),
      ...(summary?.holdsPositions ? [positions.refetch()] : []),
    ]);
  };

  async function changeCategory(categoryId: string) {
    if (!categoryTarget || locked) return;
    setWriteError(null);
    try {
      await categoryWrite.mutateAsync({
        transactionId: categoryTarget.id,
        categoryId,
        version: categoryTarget.version,
      });
      setCategoryTarget(null);
    } catch (error) {
      if (!(await conflict.handle(error, ['accounts', accountId, 'transactions']))) {
        setWriteError(error);
      }
    }
  }

  async function archiveOrRestore(restore: boolean) {
    if (!summary || !online || archiveWrite.isPending) return;
    setWriteError(null);
    try {
      await archiveWrite.mutateAsync({ version: summary.version, restore });
      setArchiveConfirmation(false);
    } catch (error) {
      if (!(await conflict.handle(error, ['accounts', accountId]))) {
        setWriteError(error);
      }
    }
  }

  if (account.isPending && !summary) return <LoadingSkeleton count={8} height={24} />;
  if (account.isError && !summary) {
    const notFound = account.error instanceof ApiError &&
      (account.error.status === 404 || problemReaction(account.error).sentCode === 'NOT_FOUND');
    return notFound ? (
      <EmptyState title={intl.formatMessage({ id: 'account.notFound' })}
        actionLabel={intl.formatMessage({ id: 'account.back' })} onAction={onBack} />
    ) : <ErrorBanner error={account.error} onRetry={() => void account.refetch()} />;
  }
  if (!summary) return null;

  return (
    <View testID="account-detail" style={{ flex: 1, gap: Spacing.three }}>
      <Pressable accessibilityRole="button" onPress={onBack} style={{ minHeight: 44, alignSelf: 'flex-start', justifyContent: 'center' }}>
        <Text style={{ color: theme.brandLink }}>{'‹ '}{intl.formatMessage({ id: 'account.back' })}</Text>
      </Pressable>
      {!online ? <OfflineBanner lastUpdated={account.dataUpdatedAt ? new Date(account.dataUpdatedAt).toISOString() : undefined} /> : null}
      {writeError ? <ErrorBanner error={writeError} onRetry={() => setWriteError(null)} /> : null}
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: Spacing.two }}>
        <View style={{ flex: 1 }}>
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{value?.portfolioName ?? ''} · {summary.accountType} · {summary.nativeCurrency}</Text>
          <Text accessibilityRole="header" style={{ ...TypeScale.headingLarge, color: theme.textPrimary }}>{summary.name}</Text>
        </View>
        <Pressable accessibilityRole="button" accessibilityLabel={intl.formatMessage({ id: 'account.actions' })}
          onPress={() => setShowActions(true)} style={{ minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: theme.textPrimary, fontSize: 24 }}>⋯</Text>
        </Pressable>
      </View>
      <View style={{ padding: Spacing.three, backgroundColor: theme.bgSurface, borderRadius: 16, gap: Spacing.two }}>
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {intl.formatMessage({ id: summary.holdsPositions ? 'account.marketValue' : summary.manualValuation ? 'account.value' : 'account.balance' })}
        </Text>
        {value ? (
          <Pressable onPress={() => setShowValue(true)} accessibilityRole="button" accessibilityLabel={intl.formatMessage({ id: 'account.explain' })} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Amount value={value.balance} currency={value.currency} valueKnown={value.valueKnown}
              style={{ ...TypeScale.headingLarge, color: theme.textPrimary }} />
            {value.currency !== value.workspaceCurrency && value.balanceInWorkspaceCurrency !== null ? (
              <Amount value={value.balanceInWorkspaceCurrency} currency={value.workspaceCurrency} approx style={{ color: theme.textSecondary }} />
            ) : null}
            <ValueStatusBadge status={value.valueStatus} valueSourceDate={value.valueSourceDate} />
            <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
              {intl.formatMessage({ id: 'account.asOf' }, { date: formatDate(value.asOf, intl.locale) })}
            </Text>
          </Pressable>
        ) : activeAccounts.isPending || archivedAccounts.isPending ? <LoadingSkeleton count={2} /> : (
          <ErrorBanner error={activeAccounts.error ?? archivedAccounts.error} onRetry={refresh} />
        )}
        {summary.reconciliation.status === 'OPEN_DIFFERENCE' ? (
          <Text style={{ color: theme.statusWarning }}>⚠ {intl.formatMessage({ id: 'account.difference' })}
            {' · '}{summary.reconciliation.asOf ? formatDate(summary.reconciliation.asOf, intl.locale) : ''}
            {summary.reconciliation.openDifference && summary.reconciliation.currency ? (
              <Amount value={summary.reconciliation.openDifference} currency={summary.reconciliation.currency} />
            ) : null}
          </Text>
        ) : summary.reconciliation.status === 'RECONCILED' ? (
          <Text style={{ color: theme.textPrimary }}>✓ {intl.formatMessage({ id: 'account.reconciled' })}
            {summary.reconciliation.asOf ? ' · ' + formatDate(summary.reconciliation.asOf, intl.locale) : ''}
          </Text>
        ) : ['NEVER','NO_OPENING_BALANCE','DISMISSED_DIFFERENCE'].includes(summary.reconciliation.status) ? (
          <Text style={{ color: theme.textSecondary }}>
            {intl.formatMessage({ id: summary.reconciliation.status === 'NEVER' ? 'account.neverReconciled' : summary.reconciliation.status === 'NO_OPENING_BALANCE' ? 'account.noOpening' : 'account.dismissedDifference' })}
          </Text>
        ) : null}
      </View>
      {archived ? (
        <View style={{ padding: Spacing.three, backgroundColor: theme.statusWarningSubtle, gap: Spacing.two }}>
          <Text style={{ color: theme.textPrimary }}>{intl.formatMessage({ id: 'account.archived' }, { date: summary.archivedAt ? summary.archivedAt.slice(0, 10) : '' })}</Text>
          <ActionButton label={intl.formatMessage({ id: 'account.restore' })} disabled={!online || archiveWrite.isPending}
            onPress={() => void archiveOrRestore(true)} />
        </View>
      ) : null}
      {summary.holdsPositions ? <ChipGroup options={[
        { value: 'positions', label: intl.formatMessage({ id: 'account.positions' }) },
        { value: 'activity', label: intl.formatMessage({ id: 'account.activity' }) },
      ]} selected={[tab]} onSelect={setTab} /> : null}
      {summary.holdsPositions && tab === 'positions' ? (
        positions.isPending ? <LoadingSkeleton count={3} /> : positions.isError ? (
          <ErrorBanner error={positions.error} onRetry={() => void positions.refetch()} />
        ) : positions.data?.positions.length ? positions.data.positions.map((position) => (
          <View key={position.securityId} style={{ padding: Spacing.two, borderBottomWidth: 1, borderBottomColor: theme.borderDefault }}>
            <Text style={{ color: theme.textPrimary }}>{position.displayName}</Text>
            {position.isin ? <Text style={{ color: theme.textSecondary }}>{position.isin}</Text> : null}
            <Amount value={position.valueInAccountCurrency} currency={positions.data.currency} />
          </View>
        )) : <EmptyState title={intl.formatMessage({ id: 'account.noPositions' })} />
      ) : !summary.hasTransactions ? (
        <EmptyState title={intl.formatMessage({ id: 'account.noLedger' })} />
      ) : (
        <>
          <ChipGroup label={intl.formatMessage({ id: 'account.filter' })} options={[
            { value: 'all', label: intl.formatMessage({ id: 'account.all' }) },
            ...(countValue > 0 ? [{ value: 'uncategorized', label: intl.formatMessage({ id: 'account.uncategorized' }, { count: countValue }) }] : []),
          ]} selected={[uncategorized && countValue > 0 ? 'uncategorized' : 'all']}
            onSelect={(next) => setUncategorized(next === 'uncategorized')} />
          {uncategorized && count.data === 0 ? (
            <ActionButton label={intl.formatMessage({ id: 'account.all' })} onPress={() => setUncategorized(false)} />
          ) : null}
          {countValue > 0 ? (
            <Pressable accessibilityRole="button" onPress={() => setUncategorized(true)}
              style={{ padding: Spacing.two, backgroundColor: theme.statusWarningSubtle, minHeight: 44 }}>
              <Text style={{ color: theme.statusWarning }}>{intl.formatMessage({ id: 'account.needsCategory' }, { count: countValue })} · {intl.formatMessage({ id: 'account.review' })}</Text>
            </Pressable>
          ) : null}
          <Pressable accessibilityRole="button" onPress={() => setShowSort(true)} style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}>
            <Text style={{ color: theme.brandLink }}>{intl.formatMessage({ id: SORT_KEYS[SORTS.indexOf(sort)] })} ⌄</Text>
          </Pressable>
          {count.isError ? <ErrorBanner error={count.error} onRetry={() => void count.refetch()} /> : null}
          {categories.isError ? <ErrorBanner error={categories.error} onRetry={() => void categories.refetch()} /> : null}
          {transactions.isPending && !transactions.data ? <LoadingSkeleton count={5} /> : null}
          {transactions.isError && !transactions.data ? <ErrorBanner error={transactions.error} onRetry={() => void transactions.refetch()} /> : null}
          {records.length === 0 && transactions.isSuccess ? (
            <EmptyState title={intl.formatMessage({ id: uncategorized ? 'account.noUncategorized' : 'account.noTransactions' })}
              description={uncategorized ? undefined : intl.formatMessage({ id: 'account.importHint' })}
              actionLabel={uncategorized ? intl.formatMessage({ id: 'account.all' }) : intl.formatMessage({ id: 'account.import' })}
              onAction={uncategorized ? () => setUncategorized(false) : onImport} />
          ) : null}
          {groupedTransactions(records, sort.startsWith('bookingDate')).map((group, index) => (
            <View key={group.date ?? `amount-${index}`}>
              {group.date ? <Text accessibilityRole="header" style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}>
                {formatRelativeDay(group.date, intl)} · {formatDate(group.date, intl.locale)}
              </Text> : null}
              {group.rows.map((row) => <TransactionLine key={row.id} transaction={row}
                categories={categories.data ?? []} archived={archived}
                onOpen={onTransaction} onCategory={setCategoryTarget} />)}
            </View>
          ))}
          {records.length ? <Text accessibilityLiveRegion="polite" style={{ color: theme.textSecondary }}>
            {intl.formatMessage({ id: 'account.showing' }, { shown: records.length, total })}
          </Text> : null}
          {transactions.isFetchNextPageError ? <ErrorBanner error={transactions.error} onRetry={() => void transactions.fetchNextPage()} /> : null}
          {transactions.hasNextPage ? <ActionButton disabled={transactions.isFetchingNextPage}
            label={intl.formatMessage({ id: 'account.loadMore' })}
            onPress={() => void transactions.fetchNextPage()} /> : null}
          {transactions.isFetchingNextPage ? <ActivityIndicator /> : null}
        </>
      )}
      <Dialog visible={showActions} title={intl.formatMessage({ id: 'account.actions' })} onClose={() => setShowActions(false)}>
        <ActionButton label={intl.formatMessage({ id: 'account.import' })} onPress={() => { setShowActions(false); onImport(); }} />
        {!archived ? <ActionButton label={intl.formatMessage({ id: 'account.archive' })}
          disabled={!online} onPress={() => { setShowActions(false); setArchiveConfirmation(true); }} /> : null}
      </Dialog>
      <Dialog visible={showSort} title={intl.formatMessage({ id: 'account.sort' })} onClose={() => setShowSort(false)}>
        {SORTS.map((choice, index) => (
          <Pressable key={choice} accessibilityRole="radio" aria-checked={sort === choice}
            onPress={() => { setSort(choice); setShowSort(false); }} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={{ color: theme.textPrimary }}>{sort === choice ? '✓ ' : ''}{intl.formatMessage({ id: SORT_KEYS[index] })}</Text>
          </Pressable>
        ))}
      </Dialog>
      <Dialog visible={categoryTarget !== null} title={intl.formatMessage({ id: 'account.changeCategory' })}
        onClose={() => setCategoryTarget(null)}>
        {categories.data?.filter((category) => category.active && category.code !== 'UNCATEGORIZED').map((category) => (
          <Pressable key={category.id} accessibilityRole="button" disabled={locked}
            onPress={() => void changeCategory(category.id)} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={{ color: theme.textPrimary }}>{categoryName(category, categories.data ?? [], intl.locale.startsWith('de'), true)}</Text>
          </Pressable>
        ))}
      </Dialog>
      <Dialog visible={archiveConfirmation} title={intl.formatMessage({ id: 'account.archive' })}
        onClose={() => setArchiveConfirmation(false)}>
        <Text style={{ color: theme.textPrimary }}>{intl.formatMessage({ id: 'account.archiveConfirm' }, { name: summary.name })}</Text>
        <ActionButton disabled={!online || archiveWrite.isPending} label={intl.formatMessage({ id: 'account.archive' })}
          onPress={() => void archiveOrRestore(false)} />
      </Dialog>
      <Dialog visible={showValue} title={intl.formatMessage({ id: 'account.explain' })} onClose={() => setShowValue(false)}>
        <Text style={{ color: theme.textPrimary }}>{value?.asOf ? formatDate(value.asOf, intl.locale) : ''}</Text>
        <Text style={{ color: theme.textSecondary }}>{value?.warnings.join(', ')}</Text>
      </Dialog>
      <ConflictDialog visible={conflict.conflicted}
        onClose={conflict.dismiss}
        onDiscard={() => { conflict.dismiss(); setCategoryTarget(null); setArchiveConfirmation(false); }}
        onReview={() => { conflict.dismiss(); refresh(); }} />
      {refreshing ? <ActivityIndicator /> : null}
    </View>
  );
}
