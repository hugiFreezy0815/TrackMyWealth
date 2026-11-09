import { useState, useSyncExternalStore, type ReactNode } from 'react';
import { onlineManager } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ActivityIndicator, Pressable, Text, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { useConflict } from '@/api/queries/conflict';
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
  ConflictDialog,
  Dialog,
  EmptyState,
  ErrorBanner,
  KeyValueList,
  LoadingSkeleton,
  OfflineBanner,
} from '@/components/shared';
import { Spacing, TypeScale, Radii } from '@/constants/theme';
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
  onClose,
  onSelect,
}: {
  categories: Category[];
  visible: boolean;
  selected: string | null;
  busy: boolean;
  onClose: () => void;
  onSelect: (id: string) => void;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const [search, setSearch] = useState('');
  const german = intl.locale.startsWith('de');
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
  return (
    <Dialog
      visible={visible}
      onClose={onClose}
      title={intl.formatMessage({ id: 'txn.picker.title' })}
    >
      <TextInput
        accessibilityLabel={intl.formatMessage({ id: 'txn.picker.search' })}
        value={search}
        onChangeText={setSearch}
        editable={!busy}
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
      <View style={{ maxHeight: 360, gap: Spacing.one }}>
        {choices.map((category) => (
          <Pressable
            key={category.id}
            accessibilityRole="button"
            aria-disabled={busy}
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
      </View>
      {busy ? <ActivityIndicator /> : null}
    </Dialog>
  );
}

/** S4: all financial values are server-supplied; edits retain their selected category on conflict. */
export function TransactionDetail({
  accountId,
  transactionId,
  onBack,
  onAccount,
}: TransactionDetailProps) {
  const intl = useIntl();
  const theme = useTheme();
  const hidden = useAmountsHidden();
  const online = useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
  const record = useTransaction(accountId, transactionId);
  const account = useAccount(accountId);
  const categories = useCategories();
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
  const transaction = record.data;
  const archived = account.data?.status === 'ARCHIVED';
  const changing =
    actions.setCategory.isPending || actions.resetCategory.isPending || actions.remove.isPending;
  const locked = !online || archived || changing;
  const date = (value: string) => formatDate(value, intl.locale);
  const german = intl.locale.startsWith('de');

  async function handleWriteError(failure: unknown): Promise<void> {
    if (failure instanceof ApiError && problemReaction(failure).sentCode === 'VERSION_REQUIRED') {
      // Do not automatically replay a destructive action with a new ETag.
      await record.refetch();
      setError(failure);
      return;
    }
    if (
      failure instanceof ApiError &&
      (failure.status === 404 || problemReaction(failure).sentCode === 'NOT_FOUND')
    ) {
      setError(failure);
      return;
    }
    if (!(await conflict.handle(failure, ['accounts', accountId, 'transactions', transactionId]))) {
      setError(failure);
    }
  }

  async function saveCategory(id: string): Promise<void> {
    if (!transaction || locked || !canChangeCategory(transaction, archived)) return;
    setSelectedCategory(id);
    setError(null);
    try {
      await actions.setCategory.mutateAsync({ categoryId: id, version: transaction.version });
      setPicker(false);
      setSelectedCategory(null);
      setNotice('txn.saved');
    } catch (failure) {
      await handleWriteError(failure);
    }
  }

  async function resetCategory(): Promise<void> {
    if (!transaction || locked || !canChangeCategory(transaction, archived)) return;
    setError(null);
    try {
      await actions.resetCategory.mutateAsync({ version: transaction.version });
      setNotice('txn.resetDone');
    } catch (failure) {
      await handleWriteError(failure);
    }
  }

  async function remove(): Promise<void> {
    if (!transaction || locked || !canRemove(transaction, archived)) return;
    setError(null);
    try {
      await actions.remove.mutateAsync({
        version: transaction.version,
        ...(transaction.removal === 'VOID' && reason.trim() ? { reason: reason.trim() } : {}),
      });
      setConfirmRemove(false);
      onBack();
    } catch (failure) {
      await handleWriteError(failure);
    }
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
  const name = transactionTitle(transaction);
  const category = categories.data?.find((item) => item.id === transaction.categoryId);
  const parent = categories.data?.find((item) => item.id === category?.parentId);
  const categoryText =
    category && categories.data
      ? categoryLabel(category, parent, german)
      : intl.formatMessage({ id: 'txn.category.fallback' });
  const changeable = canChangeCategory(transaction, archived);
  const removable = canRemove(transaction, archived);
  const moneyRow = (label: string, amount: string | null, currency: string) =>
    amount === null
      ? null
      : { key: label, label, value: <Amount value={amount} currency={currency} /> };
  const tradeRows = [
    security.data
      ? {
          key: 'security',
          label: intl.formatMessage({ id: 'txn.security' }),
          value: security.data.displayName,
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
        <Text style={{ color: theme.statusWarning }}>
          {intl.formatMessage({ id: 'txn.archived' })}
        </Text>
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
          accessibilityRole="header"
          style={{ ...TypeScale.headingMedium, color: theme.textPrimary }}
        >
          {name === 'VALUATION_ADJUSTMENT'
            ? intl.formatMessage({ id: 'account.statementAdjustment' })
            : name}
        </Text>
        <DetailLabel>
          {date(transaction.bookingDate)} · {transaction.transactionType}
        </DetailLabel>
        <Text style={{ color: theme.textSecondary }}>
          {intl.formatMessage({
            id: ['CSV', 'PDF', 'DOCUMENT', 'SPREADSHEET', 'CAMT'].includes(transaction.source)
              ? 'txn.source.imported'
              : transaction.source === 'MANUAL'
                ? 'txn.source.manual'
                : 'txn.source.bank',
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
            {changeable ? (
              <ActionButton
                label={intl.formatMessage({ id: 'txn.changeCategory' })}
                disabled={locked}
                onPress={() => {
                  setSelectedCategory(transaction.categoryId);
                  setPicker(true);
                }}
              />
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
            const message = historyDescription(entry, categories.data ?? [], german);
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
      {picker ? (
        <CategoryPicker
          visible
          categories={categories.data ?? []}
          selected={selectedCategory}
          busy={locked}
          onClose={() => setPicker(false)}
          onSelect={(id) => void saveCategory(id)}
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
        onClose={conflict.dismiss}
        onReview={() => {
          conflict.dismiss();
          void record.refetch();
        }}
        onDiscard={() => {
          conflict.dismiss();
          setPicker(false);
          setSelectedCategory(null);
          setConfirmRemove(false);
        }}
      />
    </View>
  );
}
