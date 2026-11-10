import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ComponentRef,
  type ReactNode,
} from 'react';
import { onlineManager, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import {
  ActivityIndicator,
  Platform,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native';

import { ApiError } from '@/api/client';
import { useConflict } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';
import {
  useAccount,
  useCategories,
  useSecurity,
  useTransaction,
  useTransactionDetailActions,
  useTransactionHistory,
} from '@/api/queries/resources';
import { problemReaction } from '@/api/problems';
import {
  ActionButton,
  Amount,
  BottomSheet,
  ConflictDialog,
  Dialog,
  EmptyState,
  ErrorBanner,
  KeyValueList,
  LoadingSkeleton,
  OfflineBanner,
} from '@/components/shared';
import { Spacing, TypeScale, Radii } from '@/constants/theme';
import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';
import { transactionTypeLabel } from '@/features/account-detail/model';
import { useAmountsHidden } from '@/features/privacy/amount-visibility';
import { formatDate, formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import {
  canChangeCategory,
  canRemove,
  categoryLabel,
  historyDescription,
  transactionTitle,
  type Category,
} from './model';

export type TransactionDetailProps = {
  accountId: string;
  transactionId: string;
  onBack: () => void;
  onAccount?: (id: string) => void;
  onRelatedTransaction?: (id: string) => void;
};

function DetailLabel({ children }: { children: ReactNode }) {
  const theme = useTheme();
  return <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{children}</Text>;
}

function CategoryPicker({
  categories,
  visible,
  selected,
  busy,
  error,
  onClose,
  onSelect,
  onSave,
  closeDisabled = false,
}: {
  categories: Category[];
  visible: boolean;
  selected: string | null;
  busy: boolean;
  error: string | null;
  onClose: () => void;
  onSelect: (id: string) => void;
  onSave: () => void;
  closeDisabled?: boolean;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const [search, setSearch] = useState('');
  const german = intl.locale.startsWith('de');
  const validChoice = categories.some(
    (item) => item.id === selected && item.active && item.code !== 'UNCATEGORIZED',
  );
  const choices = categories.filter((category) => {
    if (!category.active || category.code === 'UNCATEGORIZED') return false;
    const label = categoryLabel(
      category,
      categories.find((candidate) => candidate.id === category.parentId),
      german,
    );
    return label
      .toLocaleLowerCase(intl.locale)
      .includes(search.trim().toLocaleLowerCase(intl.locale));
  });
  const content = (
    <>
      <TextInput
        accessibilityLabel={intl.formatMessage({ id: 'txn.picker.search' })}
        value={search}
        onChangeText={setSearch}
        editable={!busy}
        autoCapitalize="none"
        autoCorrect={false}
        placeholder={intl.formatMessage({ id: 'txn.picker.search' })}
        placeholderTextColor={theme.textSecondary}
        style={{
          borderColor: theme.borderInteractive,
          borderWidth: 1,
          borderRadius: Radii.control,
          padding: Spacing.compact,
          color: theme.textPrimary,
          minHeight: 44,
        }}
      />
      <ScrollView
        accessibilityRole="radiogroup"
        accessibilityLabel={intl.formatMessage({ id: 'txn.picker.title' })}
        keyboardShouldPersistTaps="handled"
        style={{ maxHeight: 360 }}
        contentContainerStyle={{ gap: Spacing.one }}
      >
        {choices.map((category) => (
          <Pressable
            key={category.id}
            accessibilityRole="radio"
            aria-checked={selected === category.id}
            disabled={busy}
            onPress={() => onSelect(category.id)}
            accessibilityLabel={categoryLabel(
              category,
              categories.find((candidate) => candidate.id === category.parentId),
              german,
            )}
            style={{ minHeight: 44, justifyContent: 'center', paddingHorizontal: Spacing.two }}
          >
            <Text style={{ color: theme.textPrimary }}>
              {selected === category.id ? '✓ ' : ''}
              {categoryLabel(
                category,
                categories.find((candidate) => candidate.id === category.parentId),
                german,
              )}
            </Text>
          </Pressable>
        ))}
        {!choices.length ? (
          <Text style={{ color: theme.textSecondary }}>
            {intl.formatMessage({ id: 'txn.picker.empty' })}
          </Text>
        ) : null}
      </ScrollView>
      {error ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusLoss }}>
          {error}
        </Text>
      ) : null}
      {busy ? <ActivityIndicator /> : null}
      <ActionButton
        label={intl.formatMessage({ id: 'txn.picker.save' })}
        disabled={busy || !validChoice}
        onPress={onSave}
      />
      {Platform.OS === 'web' ? (
        <ActionButton
          label={intl.formatMessage({ id: 'txn.picker.cancel' })}
          onPress={onClose}
          disabled={busy}
        />
      ) : null}
    </>
  );
  if (Platform.OS === 'web') {
    return (
      <View
        accessibilityRole="region"
        accessibilityLabel={intl.formatMessage({ id: 'txn.picker.title' })}
        style={{
          borderWidth: 1,
          borderColor: theme.borderDefault,
          borderRadius: Radii.card,
          padding: Spacing.two,
          gap: Spacing.two,
        }}
      >
        <Text
          accessibilityRole="header"
          style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
        >
          {intl.formatMessage({ id: 'txn.picker.title' })}
        </Text>
        {content}
      </View>
    );
  }
  return (
    <BottomSheet
      visible={visible}
      onClose={onClose}
      closeDisabled={closeDisabled}
      title={intl.formatMessage({ id: 'txn.picker.title' })}
    >
      {content}
    </BottomSheet>
  );
}

/** S4: all financial values are server-supplied; edits retain their selected category on conflict. */
export function TransactionDetail({
  accountId,
  transactionId,
  onBack,
  onAccount,
  onRelatedTransaction,
}: TransactionDetailProps) {
  const intl = useIntl();
  const theme = useTheme();
  const client = useQueryClient();
  const hidden = useAmountsHidden();
  const online = useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
  const record = useTransaction(accountId, transactionId);
  const account = useAccount(accountId);
  const categories = useCategories();
  const historicalCategories = useCategories(true);
  const history = useTransactionHistory(accountId, transactionId);
  const security = useSecurity(record.data?.securityId ?? '');
  const actions = useTransactionDetailActions(accountId, transactionId);
  const conflict = useConflict();
  const [picker, setPicker] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [reason, setReason] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [categoryError, setCategoryError] = useState<string | null>(null);
  const [categoryConflict, setCategoryConflict] = useState(false);
  const [pendingReview, setPendingReview] = useState(false);
  // ETag confirmed by the conflict review; never infer it from an old cached row.
  const [reviewVersion, setReviewVersion] = useState<number | null>(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  // Mutation promises may settle before React Query publishes isPending.
  const categorySaving = useRef(false);
  const [categorySubmitting, setCategorySubmitting] = useState(false);
  const [serverArchived, setServerArchived] = useState(false);
  const categoryControl = useRef<ComponentRef<typeof Pressable>>(null);
  const detailHeading = useRef<ComponentRef<typeof Text>>(null);
  const transaction = record.data;
  const archived = serverArchived || account.data?.status === 'ARCHIVED';
  const changing =
    categorySubmitting ||
    actions.setCategory.isPending ||
    actions.resetCategory.isPending ||
    actions.remove.isPending;
  const locked =
    !online ||
    archived ||
    changing ||
    record.isPlaceholderData ||
    record.isFetching ||
    record.isError;
  useEffect(() => {
    // A desktop detail panel is not a route transition; announce its loaded heading.
    if (Platform.OS === 'web' && transaction && !record.isPlaceholderData) {
      moveAccessibilityFocus(detailHeading.current);
    }
  }, [transaction?.id, record.isPlaceholderData]);

  const date = (value: string) => formatDate(value, intl.locale);
  const german = intl.locale.startsWith('de');

  const focusCategory = () => {
    if (Platform.OS === 'web') moveAccessibilityFocus(categoryControl.current);
    else requestAnimationFrame(() => moveAccessibilityFocus(categoryControl.current));
  };
  const closePicker = () => {
    if (categorySaving.current || changing) return;
    setPicker(false);
    setSelectedCategory(null);
    setCategoryError(null);
    setReviewVersion(null);
    focusCategory();
  };
  const categoryIsValid = (id: string) =>
    !!categories.data?.some(
      (item) => item.id === id && item.active && item.code !== 'UNCATEGORIZED',
    );
  const chooseCategory = (id: string) => {
    setSelectedCategory(id);
    setCategoryError(null);
  };
  const saveSelectedCategory = () => {
    if (selectedCategory) void saveCategory(selectedCategory);
  };

  async function withVersionRetry<T>(
    perform: (version: number) => Promise<T>,
    version: number,
  ): Promise<T> {
    try {
      return await perform(version);
    } catch (failure) {
      if (failure instanceof ApiError && problemReaction(failure).sentCode === 'VERSION_REQUIRED') {
        const refreshed = await record.refetch();
        if (refreshed.data) return perform(refreshed.data.version);
      }
      throw failure;
    }
  }

  const isTransactionMissing = (failure: unknown) =>
    failure instanceof ApiError &&
    (failure.status === 404 || problemReaction(failure).sentCode === 'NOT_FOUND');

  const isAccountArchived = (failure: unknown) =>
    failure instanceof ApiError && problemReaction(failure).sentCode === 'ACCOUNT_ARCHIVED';

  const refreshAfterServerChange = () => {
    void invalidateFinancialData(client);
    void account.refetch();
  };

  function handleTerminalWriteError(failure: unknown): boolean {
    if (isTransactionMissing(failure)) {
      setPicker(false);
      setSelectedCategory(null);
      setPendingReview(false);
      setReviewVersion(null);
      setError(failure);
      client.removeQueries({ queryKey: queryKeys.transaction(accountId, transactionId), exact: true });
      refreshAfterServerChange();
      return true;
    }
    if (isAccountArchived(failure)) {
      setServerArchived(true);
      setPicker(false);
      setSelectedCategory(null);
      setPendingReview(false);
      setReviewVersion(null);
      setError(failure);
      refreshAfterServerChange();
      return true;
    }
    return false;
  }

  async function handleWriteError(failure: unknown): Promise<void> {
    if (handleTerminalWriteError(failure)) return;
    if (!(await conflict.handle(failure, ['accounts', accountId, 'transactions', transactionId]))) {
      setError(failure);
    }
  }

  async function saveCategory(id: string): Promise<void> {
    if (
      categorySaving.current ||
      !transaction ||
      locked ||
      !canChangeCategory(transaction, archived) ||
      !categoryIsValid(id) ||
      (pendingReview && reviewVersion === null)
    )
      return;
    categorySaving.current = true;
    setCategorySubmitting(true);
    setError(null);
    setCategoryError(null);
    try {
      await withVersionRetry(
        (version) => actions.setCategory.mutateAsync({ categoryId: id, version }),
        pendingReview && reviewVersion !== null ? reviewVersion : transaction.version,
      );
      setPicker(false);
      setSelectedCategory(null);
      setPendingReview(false);
      setReviewVersion(null);
      setNotice('txn.saved');
      focusCategory();
    } catch (failure) {
      if (handleTerminalWriteError(failure)) return;
      const code = failure instanceof ApiError ? problemReaction(failure).sentCode : null;
      if (code === 'VERSION_CONFLICT') {
        // Never render a native sheet and a conflict modal at once.
        setPicker(false);
        setCategoryConflict(true);
        setPendingReview(false);
        setReviewVersion(null);
        await conflict.handle(failure, ['accounts', accountId, 'transactions', transactionId]);
        return;
      }
      if (code === 'UNPROCESSABLE' || code === 'VALIDATION_FAILED') {
        setCategoryError(intl.formatMessage({ id: 'txn.category.unavailable' }));
        const refreshed = await categories.refetch();
        if (
          !refreshed.data?.some(
            (item) => item.id === id && item.active && item.code !== 'UNCATEGORIZED',
          )
        ) {
          setSelectedCategory(null);
        }
      } else {
        setCategoryError(intl.formatMessage({ id: 'txn.category.saveFailed' }));
      }
    } finally {
      categorySaving.current = false;
      setCategorySubmitting(false);
    }
  }

  async function resetCategory(): Promise<void> {
    if (!transaction || locked || !canChangeCategory(transaction, archived)) return;
    setError(null);
    try {
      await withVersionRetry(
        (version) => actions.resetCategory.mutateAsync({ version }),
        transaction.version,
      );
      setNotice('txn.resetDone');
    } catch (failure) {
      await handleWriteError(failure);
    }
  }

  async function remove(): Promise<void> {
    if (!transaction || locked || !canRemove(transaction, archived)) return;
    setError(null);
    try {
      await withVersionRetry(
        (version) =>
          actions.remove.mutateAsync({
            version,
            ...(transaction.removal === 'VOID' && reason.trim() ? { reason: reason.trim() } : {}),
          }),
        transaction.version,
      );
      setConfirmRemove(false);
      onBack();
    } catch (failure) {
      setConfirmRemove(false);
      await handleWriteError(failure);
    }
  }

  /** Do not expose Save again until the server has confirmed the fresh version. */
  const reloadCategoryReview = () => {
    setReviewLoading(true);
    setPendingReview(false);
    setReviewVersion(null);
    setCategoryError(null);
    void Promise.all([record.refetch(), categories.refetch()])
      .then(([fresh, latestCategories]) => {
        if (
          !fresh.data ||
          fresh.error ||
          fresh.isPlaceholderData ||
          latestCategories.error ||
          !latestCategories.data
        ) {
          setCategoryError(intl.formatMessage({ id: 'txn.category.refreshFailed' }));
          setPendingReview(true);
          return;
        }
        setReviewVersion(fresh.data.version);
        const pending = selectedCategory;
        if (
          pending &&
          !latestCategories.data.some(
            (item) => item.id === pending && item.active && item.code !== 'UNCATEGORIZED',
          )
        ) {
          setSelectedCategory(null);
          setCategoryError(intl.formatMessage({ id: 'txn.category.unavailable' }));
        }
        setPendingReview(true);
      })
      .catch(() => {
        setCategoryError(intl.formatMessage({ id: 'txn.category.refreshFailed' }));
        setPendingReview(true);
      })
      .finally(() => {
        setReviewLoading(false);
        focusCategory();
      });
  };

  const reviewConflict = () => {
    conflict.dismiss();
    if (!categoryConflict) {
      void record.refetch();
      return;
    }
    setCategoryConflict(false);
    reloadCategoryReview();
  };

  // Do not expose cached transaction amounts or merchant data after authorization fails.
  // Includes revalidation of both the transaction and its parent account.
  const accessFailure = [record.error, account.error].find(
    (failure) =>
      failure instanceof ApiError &&
      (failure.status === 401 || failure.status === 403 || failure.status === 404),
  );
  if (accessFailure instanceof ApiError) {
    return accessFailure.status === 404 ? (
      <EmptyState
        title={intl.formatMessage({ id: 'txn.notFound' })}
        actionLabel={intl.formatMessage({ id: 'txn.back' })}
        onAction={onBack}
      />
    ) : (
      <ErrorBanner error={accessFailure} />
    );
  }
  if (record.isError && isTransactionMissing(record.error)) {
    return (
      <EmptyState
        title={intl.formatMessage({ id: 'txn.notFound' })}
        actionLabel={intl.formatMessage({ id: 'txn.back' })}
        onAction={onBack}
      />
    );
  }
  if (error && isTransactionMissing(error)) {
    return (
      <EmptyState
        title={intl.formatMessage({ id: 'txn.notFound' })}
        actionLabel={intl.formatMessage({ id: 'txn.back' })}
        onAction={onBack}
      />
    );
  }
  if (!transaction && record.isPending) return <LoadingSkeleton count={6} height={24} />;
  if (!transaction) {
    const notFound = record.error instanceof ApiError && record.error.status === 404;
    return notFound ? (
      <EmptyState
        title={intl.formatMessage({ id: 'txn.notFound' })}
        actionLabel={intl.formatMessage({ id: 'txn.back' })}
        onAction={onBack}
      />
    ) : (
      <ErrorBanner error={record.error} onRetry={() => void record.refetch()} />
    );
  }
  const name =
    transactionTitle(transaction) === transaction.transactionType
      ? transactionTypeLabel(transaction.transactionType, intl)
      : transactionTitle(transaction);
  const allCategories = historicalCategories.data ?? categories.data ?? [];
  const category = allCategories.find((item) => item.id === transaction.categoryId);
  const parent = allCategories.find((item) => item.id === category?.parentId);
  const categoryText =
    category && allCategories.length
      ? categoryLabel(category, parent, german)
      : intl.formatMessage({ id: 'txn.category.fallback' });
  const pendingCategory = categories.data?.find((item) => item.id === selectedCategory);
  const pendingParent = categories.data?.find((item) => item.id === pendingCategory?.parentId);
  const pendingLabel = pendingCategory
    ? categoryLabel(pendingCategory, pendingParent, german)
    : intl.formatMessage({ id: 'txn.category.fallback' });
  const changeable = canChangeCategory(transaction, archived);
  const removable = canRemove(transaction, archived);
  const categoryEditExplanation =
    archived
      ? 'txn.archived'
      : transaction.voidedAt || transaction.deletedAt
        ? 'txn.category.removed'
        : transaction.reconciliationAdjustment
          ? 'txn.adjustmentLocked'
          : !changeable
            ? 'txn.category.locked'
            : null;
  const moneyRow = (label: string, amount: string | null, currency: string) =>
    amount === null
      ? null
      : { key: label, label, value: <Amount value={amount} currency={currency} /> };
  const tradeRows = [
    transaction.securityId
      ? {
          key: 'security',
          label: intl.formatMessage({ id: 'txn.security' }),
          value: security.isError ? (
            <View style={{ gap: Spacing.one }}>
              <ErrorBanner error={security.error} onRetry={() => void security.refetch()} />
            </View>
          ) : security.data?.displayName ?? (
            <LoadingSkeleton count={1} />
          ),
        }
      : null,
    transaction.quantity
      ? {
          key: 'quantity',
          label: intl.formatMessage({ id: 'txn.quantity' }),
          value: transaction.quantity,
        }
      : null,
    moneyRow(
      intl.formatMessage({ id: 'txn.unitPrice' }),
      transaction.unitPrice,
      transaction.currency,
    ),
    moneyRow(intl.formatMessage({ id: 'txn.fee' }), transaction.feeAmount, transaction.currency),
    transaction.tradeDate
      ? {
          key: 'tradeDate',
          label: intl.formatMessage({ id: 'txn.tradeDate' }),
          value: date(transaction.tradeDate),
        }
      : null,
    transaction.settlementDate
      ? {
          key: 'settlementDate',
          label: intl.formatMessage({ id: 'txn.settlementDate' }),
          value: date(transaction.settlementDate),
        }
      : null,
    moneyRow(
      intl.formatMessage({ id: 'txn.gross' }),
      transaction.grossAmount,
      transaction.currency,
    ),
    moneyRow(
      intl.formatMessage({ id: 'txn.taxWithheld' }),
      transaction.taxWithheldAmount,
      transaction.currency,
    ),
    moneyRow(intl.formatMessage({ id: 'txn.net' }), transaction.netAmount, transaction.currency),
  ].filter((item): item is NonNullable<typeof item> => item !== null);
  const rows = [
    {
      key: 'account',
      label: intl.formatMessage({ id: 'txn.account' }),
      value: [transaction.portfolioName, transaction.accountName].filter(Boolean).join(' · '),
    },
    {
      key: 'date',
      label: intl.formatMessage({ id: 'txn.bookingDate' }),
      value: date(transaction.bookingDate),
    },
    ...(transaction.internalTransfer
      ? [
          {
            key: 'transfer',
            label: intl.formatMessage({ id: 'txn.category' }),
            value: intl.formatMessage({ id: 'txn.internalTransfer' }),
          },
        ]
      : []),
    ...(transaction.counterpartyName
      ? [
          {
            key: 'counterparty',
            label: intl.formatMessage({ id: 'txn.counterparty' }),
            value: transaction.counterpartyName,
          },
        ]
      : []),
    ...(transaction.counterpartyAccountName
      ? [
          {
            key: 'otherAccount',
            label: intl.formatMessage({ id: 'txn.transferAccount' }),
            value: transaction.counterpartyAccountName,
          },
        ]
      : []),
    {
      key: 'notes',
      label: intl.formatMessage({ id: 'txn.notes' }),
      value: transaction.notes ?? '–',
    },
    ...tradeRows,
  ];

  return (
    <View testID="transaction-detail" style={{ gap: Spacing.three }}>
      <Pressable
        accessibilityRole="button"
        onPress={onBack}
        style={{ minHeight: 44, alignSelf: 'flex-start', justifyContent: 'center' }}
      >
        <Text style={{ color: theme.brandLink }}>{intl.formatMessage({ id: 'txn.back' })}</Text>
      </Pressable>
      {!online ? (
        <OfflineBanner
          lastUpdated={
            record.dataUpdatedAt ? new Date(record.dataUpdatedAt).toISOString() : undefined
          }
        />
      ) : null}
      {archived ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
          {intl.formatMessage({ id: 'txn.archived' })}
        </Text>
      ) : null}
      {record.isError && !isTransactionMissing(record.error) ? (
        <ErrorBanner error={record.error} onRetry={() => void record.refetch()} />
      ) : null}
      {error ? (
        <ErrorBanner
          error={error}
          onRetry={() => {
            setError(null);
            void record.refetch();
          }}
        />
      ) : null}
      {notice ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusGain }}>
          {intl.formatMessage({ id: notice })}
        </Text>
      ) : null}
      <View
        style={{
          backgroundColor: theme.bgSurface,
          borderRadius: Radii.card,
          padding: Spacing.three,
          gap: Spacing.two,
        }}
      >
        <Amount
          value={transaction.amount}
          currency={transaction.currency}
          signRole="change"
          style={{
            ...TypeScale.headingLarge,
            color: theme.textPrimary,
            textDecorationLine: transaction.voidedAt ? 'line-through' : 'none',
          }}
        />
        <Text
          ref={detailHeading}
          accessibilityRole="header"
          tabIndex={-1}
          style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
        >
          {name === 'VALUATION_ADJUSTMENT'
            ? intl.formatMessage({ id: 'account.statementAdjustment' })
            : name}
        </Text>
        <DetailLabel>
          {date(transaction.bookingDate)} ·{' '}
          {transactionTypeLabel(transaction.transactionType, intl)}
        </DetailLabel>
        <Text style={{ color: theme.textSecondary }}>
          {intl.formatMessage({
            id: ['CSV', 'PDF', 'DOCUMENT', 'SPREADSHEET', 'CAMT'].includes(transaction.source)
              ? 'txn.source.imported'
              : transaction.source === 'MANUAL'
                ? 'txn.source.manual'
                : ['API', 'AGGREGATOR'].includes(transaction.source)
                  ? 'txn.source.bank'
                  : 'txn.source.unknown',
          })}
          {transaction.fxRateEstimated ? ' · ' + intl.formatMessage({ id: 'txn.fxEstimated' }) : ''}
        </Text>
        {transaction.voidedAt ? (
          <Text style={{ color: theme.statusWarning }}>
            {intl.formatMessage(
              { id: 'txn.voided' },
              {
                date: formatDateTime(transaction.voidedAt, intl.locale),
                reason: transaction.voidReason ?? '',
              },
            )}
          </Text>
        ) : null}
        {transaction.replacesTransactionId ? (
          <DetailLabel>{intl.formatMessage({ id: 'txn.reversal' })}</DetailLabel>
        ) : null}
        {transaction.correctsTransactionId ? (
          <DetailLabel>{intl.formatMessage({ id: 'txn.corrected' })}</DetailLabel>
        ) : null}
        {transaction.restoresTransactionId ? (
          <DetailLabel>{intl.formatMessage({ id: 'txn.restored' })}</DetailLabel>
        ) : null}
        {transaction.reconciliationAdjustment ? (
          <DetailLabel>{intl.formatMessage({ id: 'txn.adjustment' })}</DetailLabel>
        ) : null}
        {transaction.warnings.map((warning) => (
          <Text key={warning} style={{ color: theme.statusWarning }}>
            ⚠ {intl.formatMessage({ id: 'txn.warning' })}
          </Text>
        ))}
      </View>
      <View
        style={{
          backgroundColor: theme.bgSurface,
          borderRadius: Radii.card,
          padding: Spacing.three,
          gap: Spacing.two,
        }}
      >
        <KeyValueList items={rows} />
        {transaction.counterpartyAccountId && onAccount ? (
          <ActionButton
            label={intl.formatMessage({ id: 'txn.openAccount' })}
            onPress={() => onAccount(transaction.counterpartyAccountId!)}
          />
        ) : null}
        {transaction.categoryId !== null && !transaction.internalTransfer ? (
          <View style={{ gap: Spacing.one }}>
            <DetailLabel>{intl.formatMessage({ id: 'txn.category' })}</DetailLabel>
            <Text style={{ color: theme.textPrimary }}>
              {categoryText} ·{' '}
              {intl.formatMessage({
                id:
                  transaction.categoryAssignedBy === 'USER'
                    ? 'txn.category.member'
                    : 'txn.category.automatic',
              })}
            </Text>
            {!changeable && categoryEditExplanation && !pendingReview ? (
              <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
                {intl.formatMessage({ id: categoryEditExplanation })}
              </Text>
            ) : null}
            {changeable ? (
              <Pressable
                ref={categoryControl}
                accessibilityRole="button"
                accessibilityLabel={intl.formatMessage({ id: 'txn.changeCategory' })}
                disabled={locked}
                aria-disabled={locked}
                aria-expanded={picker}
                onPress={() => {
                  setSelectedCategory(transaction.categoryId);
                  setCategoryError(null);
                  setPendingReview(false);
                  setReviewVersion(null);
                  setPicker(true);
                }}
                style={{ minHeight: 44, justifyContent: 'center' }}
              >
                <Text style={{ color: theme.brandLink }}>
                  {intl.formatMessage({ id: 'txn.changeCategory' })}
                </Text>
              </Pressable>
            ) : null}
            {Platform.OS === 'web' && picker ? (
              <CategoryPicker
                visible
                categories={categories.data ?? []}
                selected={selectedCategory}
                busy={locked}
                error={categoryError}
                onClose={closePicker}
                onSelect={chooseCategory}
                onSave={saveSelectedCategory}
              />
            ) : null}
            {reviewLoading ? <ActivityIndicator /> : null}
            {pendingReview ? (
              <View style={{ gap: Spacing.two }}>
                <Text accessibilityRole="alert" style={{ color: theme.textPrimary }}>
                  {intl.formatMessage({ id: 'txn.picker.pending' }, { category: pendingLabel })}
                </Text>
                {categoryError ? (
                  <Text accessibilityRole="alert" style={{ color: theme.statusLoss }}>
                    {categoryError}
                  </Text>
                ) : null}
                {reviewVersion === null ? (
                  <ActionButton
                    label={intl.formatMessage({ id: 'txn.picker.retryReview' })}
                    disabled={reviewLoading}
                    onPress={reloadCategoryReview}
                  />
                ) : null}
                <ActionButton
                  label={intl.formatMessage({ id: 'txn.picker.saveAgain' })}
                  disabled={
                    locked ||
                    reviewVersion === null ||
                    !changeable ||
                    !selectedCategory ||
                    !categoryIsValid(selectedCategory)
                  }
                  onPress={() => {
                    if (selectedCategory) void saveCategory(selectedCategory);
                  }}
                />
                {!changeable && categoryEditExplanation ? (
                  <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
                    {intl.formatMessage({ id: categoryEditExplanation })}
                  </Text>
                ) : null}
                <ActionButton
                  label={intl.formatMessage({ id: 'txn.picker.discard' })}
                  onPress={() => {
                    setPendingReview(false);
                    setReviewVersion(null);
                    setSelectedCategory(null);
                    setCategoryError(null);
                    focusCategory();
                  }}
                />
              </View>
            ) : null}
            {changeable && transaction.categoryAssignedBy === 'USER' ? (
              <ActionButton
                label={intl.formatMessage({ id: 'txn.resetCategory' })}
                disabled={locked}
                onPress={() => void resetCategory()}
              />
            ) : null}
          </View>
        ) : null}
        <Pressable
          accessibilityRole="button"
          aria-expanded={advanced}
          onPress={() => setAdvanced(!advanced)}
          style={{ minHeight: 44, justifyContent: 'center' }}
        >
          <Text style={{ color: theme.brandLink }}>
            {intl.formatMessage({ id: 'txn.advanced' })} {advanced ? '▴' : '▾'}
          </Text>
        </Pressable>
        {advanced ? (
          <KeyValueList
            items={[
              ...(transaction.bankTransactionCode
                ? [
                    {
                      key: 'bankCode',
                      label: intl.formatMessage({ id: 'txn.bankCode' }),
                      value: transaction.bankTransactionCode,
                    },
                  ]
                : []),
              ...(transaction.importBatch
                ? [
                    {
                      key: 'source',
                      label: intl.formatMessage({ id: 'txn.sourceDetail' }),
                      value: `${transaction.importBatch.fileFormat} · ${formatDateTime(transaction.importBatch.uploadedAt, intl.locale)}`,
                    },
                  ]
                : []),
              ...(transaction.mcc
                ? [
                    {
                      key: 'mcc',
                      label: intl.formatMessage({ id: 'txn.mcc' }),
                      value: transaction.mcc,
                    },
                  ]
                : []),
              ...(transaction.externalId
                ? [
                    {
                      key: 'external',
                      label: intl.formatMessage({ id: 'txn.externalId' }),
                      value: transaction.externalId,
                    },
                  ]
                : []),
              ...(transaction.amountInAccountCurrency !== null &&
              account.data &&
              transaction.currency !== account.data.nativeCurrency
                ? [
                    {
                      key: 'conversion',
                      label: intl.formatMessage({ id: 'txn.converted' }),
                      value: (
                        <Amount
                          value={transaction.amountInAccountCurrency}
                          currency={account.data.nativeCurrency}
                          approx
                        />
                      ),
                    },
                  ]
                : []),
              ...(transaction.fxRateToAccountCurrency && !hidden
                ? [
                    {
                      key: 'fx',
                      label: intl.formatMessage({ id: 'txn.fxRate' }),
                      value: transaction.fxRateToAccountCurrency,
                    },
                  ]
                : []),
            ]}
          />
        ) : null}
      </View>
      <View style={{ gap: Spacing.two }}>
        <Text
          accessibilityRole="header"
          style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
        >
          {intl.formatMessage({ id: 'txn.history.title' })}
        </Text>
        {history.isPending ? <LoadingSkeleton count={2} /> : null}
        {history.isError ? (
          <ErrorBanner error={history.error} onRetry={() => void history.refetch()} />
        ) : null}
        {history.data?.pages
          .flatMap((page) => page.content)
          .map((entry, index) => {
            const message = historyDescription(entry, allCategories, german);
            const relatedId = entry.relatedTransactionId;
            return (
              <View
                key={`${entry.occurredAt}-${index}`}
                style={{
                  paddingVertical: Spacing.two,
                  borderBottomWidth: 1,
                  borderBottomColor: theme.borderDefault,
                }}
              >
                <Text style={{ color: theme.textPrimary }}>
                  {intl.formatMessage({ id: message.key }, message.values)}
                </Text>
                <DetailLabel>{formatDateTime(entry.occurredAt, intl.locale)}</DetailLabel>
                {relatedId && entry.kind === 'CORRECTED' && onRelatedTransaction ? (
                  <Pressable
                    accessibilityRole="link"
                    onPress={() => onRelatedTransaction(relatedId)}
                    style={{ minHeight: 44, justifyContent: 'center' }}
                  >
                    <Text style={{ color: theme.brandLink }}>
                      {intl.formatMessage({ id: 'txn.history.openCorrected' })}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            );
          })}
        {history.hasNextPage ? (
          <ActionButton
            label={intl.formatMessage({ id: 'txn.history.more' })}
            disabled={history.isFetchingNextPage}
            onPress={() => void history.fetchNextPage()}
          />
        ) : null}
        {history.isFetchNextPageError ? (
          <ErrorBanner error={history.error} onRetry={() => void history.fetchNextPage()} />
        ) : null}
      </View>
      {removable ? (
        <View style={{ gap: Spacing.two }}>
          <Text style={{ color: theme.textSecondary }}>
            {intl.formatMessage({ id: 'txn.correctionInfo' })}
          </Text>
          <ActionButton
            label={intl.formatMessage({ id: 'txn.delete' })}
            disabled={locked}
            onPress={() => setConfirmRemove(true)}
          />
        </View>
      ) : transaction.reconciliationAdjustment ? (
        <Text style={{ color: theme.textSecondary }}>
          {intl.formatMessage({ id: 'txn.adjustmentLocked' })}
        </Text>
      ) : null}

      {Platform.OS !== 'web' && picker ? (
        <CategoryPicker
          visible
          categories={categories.data ?? []}
          selected={selectedCategory}
          busy={locked}
          error={categoryError}
          onClose={closePicker}
          onSelect={chooseCategory}
          onSave={saveSelectedCategory}
          closeDisabled={changing}
        />
      ) : null}
      <Dialog
        visible={confirmRemove}
        onClose={() => setConfirmRemove(false)}
        title={intl.formatMessage({ id: 'txn.delete' })}
      >
        <Text style={{ color: theme.textPrimary }}>
          {intl.formatMessage({
            id: transaction.removal === 'VOID' ? 'txn.deleteVoidConfirm' : 'txn.deleteSoftConfirm',
          })}
        </Text>
        {transaction.removal === 'VOID' ? (
          <TextInput
            accessibilityLabel={intl.formatMessage({ id: 'txn.deleteReason' })}
            value={reason}
            onChangeText={setReason}
            maxLength={200}
            editable={!locked}
            placeholder={intl.formatMessage({ id: 'txn.deleteReason' })}
            placeholderTextColor={theme.textSecondary}
            style={{
              color: theme.textPrimary,
              borderWidth: 1,
              borderColor: theme.borderInteractive,
              borderRadius: Radii.control,
              padding: Spacing.compact,
              minHeight: 44,
            }}
          />
        ) : null}
        <ActionButton
          label={intl.formatMessage({ id: 'txn.confirmDelete' })}
          disabled={locked}
          onPress={() => void remove()}
        />
      </Dialog>
      <ConflictDialog
        visible={conflict.conflicted}
        onClose={reviewConflict}
        onReview={reviewConflict}
        onDiscard={() => {
          conflict.dismiss();
          setPicker(false);
          setSelectedCategory(null);
          setCategoryError(null);
          setPendingReview(false);
          setCategoryConflict(false);
          setReviewVersion(null);
          setConfirmRemove(false);
          focusCategory();
        }}
      />
    </View>
  );
}
