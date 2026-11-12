import { useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { ScrollView, Text, useWindowDimensions } from 'react-native';

import { ApiError } from '@/api/client';
import { problemReaction } from '@/api/problems';
import { ifMatch } from '@/api/queries/conflict';
import { queryKeys } from '@/api/queries/keys';
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';
import { ActionButton, Dialog, ErrorBanner, LoadingSkeleton } from '@/components/shared';
import { useTheme } from '@/hooks/use-theme';

type Account = components['schemas']['AccountSummaryResponse'];
type AccountRow = components['schemas']['AccountListItemResponse'];
type TransferFlags = Pick<Account, 'hasTransactions' | 'hasStatementCycle' | 'hasAmortisation'>;
const directoryKey = [...queryKeys.accounts(), 'savings-pocket-directory'] as const;
/** A list read this recently (e.g. when the actions menu opened) is reused by the picker. */
const DIRECTORY_FRESH_MS = 5_000;

/** Where the picker opens: the account detail menu, or an import row's pocket warning. */
type PocketContext = 'account' | 'import';

function canHoldTransfers(account: TransferFlags) {
  return account.hasTransactions && !account.hasStatementCycle && !account.hasAmortisation;
}

export function pocketChoices(source: Account, accounts: readonly AccountRow[]) {
  const occupied = new Set(
    accounts.filter((account) => account.id !== source.id).map((a) => a.savingsPocketAccountId),
  );
  return accounts.filter(
    (candidate) =>
      candidate.id !== source.id &&
      candidate.id !== source.savingsPocketAccountId &&
      !candidate.archived &&
      candidate.currency === source.nativeCurrency &&
      canHoldTransfers(candidate) &&
      candidate.savingsPocketAccountId === null &&
      !occupied.has(candidate.id),
  );
}

/** The linked pocket when it can take the account's moves (the server's `usablePocket`). */
function usablePocket(source: Account, accounts: readonly AccountRow[]) {
  const pocket = accounts.find(({ id }) => id === source.savingsPocketAccountId);
  return pocket &&
    !pocket.archived &&
    pocket.currency === source.nativeCurrency &&
    canHoldTransfers(pocket)
    ? pocket
    : undefined;
}

async function readDirectory(signal: AbortSignal) {
  const [active, archived] = await Promise.all([
    typedApi.get('/api/v1/accounts', { signal }),
    typedApi.get('/api/v1/accounts', { query: { archived: true }, signal }),
  ]);
  return [...active, ...archived];
}

/**
 * #466: links another account as `account`'s savings pocket. The workspace's account lists (the
 * "directory") are read only while `load` is set or the picker is open, so the account detail
 * reads them when its menu opens, not on every visit.
 */
export function useSavingsPocketLink({
  account,
  disabled,
  context,
  load,
}: {
  account?: Account;
  disabled: boolean;
  context: PocketContext;
  load: boolean;
}) {
  const intl = useIntl();
  const theme = useTheme();
  const { height } = useWindowDimensions();
  const t = (id: string, values?: Record<string, string>) => intl.formatMessage({ id }, values);
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<unknown>();
  const [changed, setChanged] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [reloadFailed, setReloadFailed] = useState(false);
  const locked = useRef(false);
  const eligible = !!account && account.status === 'ACTIVE' && canHoldTransfers(account);
  const directory = useQuery({
    queryKey: directoryKey,
    queryFn: ({ signal }) => readDirectory(signal),
    enabled: eligible && !disabled && (load || open),
    staleTime: 30_000,
  });
  const pocketId = account?.savingsPocketAccountId ?? null;
  const currentPocket = useQuery({
    queryKey: queryKeys.account(pocketId ?? ''),
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/accounts/{accountId}', {
        params: { accountId: pocketId ?? '' },
        signal,
      }),
    enabled: context === 'account' && eligible && !!pocketId,
  });
  const write = useMutation({
    mutationFn: ({ source, target }: { source: Account; target: AccountRow }) =>
      typedApi.put('/api/v1/accounts/{accountId}/savings-pocket', {
        params: { accountId: source.id },
        version: ifMatch(source),
        body: { savingsPocketAccountId: target.id },
      }),
  });

  const close = () => {
    if (locked.current) return;
    setOpen(false);
    if (!reloadFailed) setError(undefined);
    setChanged(false);
  };
  const refresh = async (source: Account) => {
    setReloading(true);
    try {
      const accountKey = queryKeys.account(source.id);
      await client.cancelQueries({ queryKey: accountKey, exact: true });
      // Bypass the fresh cache, and keep the last account visible if the GET fails.
      const latest = await typedApi.get('/api/v1/accounts/{accountId}', {
        params: { accountId: source.id },
      });
      client.setQueryData(accountKey, latest);
      await directory.refetch({ throwOnError: true });
      setError(undefined);
      setChanged(true);
      setReloadFailed(false);
    } catch (failure) {
      setError(failure);
      setReloadFailed(true);
    } finally {
      setReloading(false);
    }
  };
  const choose = async (source: Account, target: AccountRow) => {
    if (locked.current || disabled || reloading || reloadFailed) return;
    locked.current = true;
    setError(undefined);
    try {
      const updated = await write.mutateAsync({ source, target });
      // The API builds its strong ETag from this same version.
      await Promise.all([
        client.cancelQueries({ queryKey: queryKeys.account(source.id), exact: true }),
        client.cancelQueries({ queryKey: directoryKey, exact: true }),
      ]);
      client.setQueryData(queryKeys.account(source.id), updated);
      client.setQueryData<AccountRow[]>(directoryKey, (previous) =>
        previous?.map((item) =>
          item.id === source.id
            ? { ...item, savingsPocketAccountId: updated.savingsPocketAccountId }
            : item,
        ),
      );
      setOpen(false);
      setChanged(false);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        await refresh(source);
      } else {
        setError(failure);
        if (failure instanceof ApiError && problemReaction(failure).reaction === 'FIX_INPUT') {
          void directory.refetch();
        }
      }
    } finally {
      locked.current = false;
    }
  };

  const isPocket =
    !!account && directory.data?.some((i) => i.savingsPocketAccountId === account.id);
  const choices = account && directory.data ? pocketChoices(account, directory.data) : [];
  const usable = account && directory.data ? usablePocket(account, directory.data) : undefined;
  // Offered when there is something to choose, or a pocket to replace (the empty list then says
  // why); never on another account's pocket, which the server refuses.
  const offered =
    eligible &&
    !!directory.data &&
    !directory.isError &&
    !isPocket &&
    (!!choices.length || !!pocketId);
  const label = t(pocketId ? 'savingsPocket.change' : 'savingsPocket.link');
  const current = currentPocket.data;

  /** The action, or its loading/error state while the directory is read. */
  const trigger = (onPress?: () => void): ReactNode => {
    if (!eligible) return null;
    if (directory.isError && !open) {
      return <ErrorBanner error={directory.error} onRetry={() => void directory.refetch()} />;
    }
    if (directory.isLoading && !open) return <LoadingSkeleton count={1} />;
    return offered ? (
      <ActionButton
        label={label}
        disabled={disabled}
        onPress={() => {
          onPress?.();
          // Accounts added or linked elsewhere since the list was read show up as choices.
          if (Date.now() - directory.dataUpdatedAt > DIRECTORY_FRESH_MS) void directory.refetch();
          setOpen(true);
        }}
      />
    ) : null;
  };

  const dialog =
    account && eligible ? (
      <Dialog
        visible={open}
        title={label}
        onClose={close}
        closeDisabled={write.isPending || reloading}
      >
        <Text style={{ color: theme.textSecondary }}>
          {t(context === 'import' ? 'savingsPocket.thisImport' : 'savingsPocket.futureOnly')}
        </Text>
        {changed ? (
          <Text accessibilityRole="alert" style={{ color: theme.textPrimary }}>
            {t('savingsPocket.reloaded')}
          </Text>
        ) : null}
        {error ? (
          <ErrorBanner
            error={error}
            onRetry={reloadFailed ? () => void refresh(account) : undefined}
          />
        ) : null}
        {directory.isError && !reloadFailed ? (
          <ErrorBanner error={directory.error} onRetry={() => void directory.refetch()} />
        ) : null}
        {directory.isFetching || reloading ? (
          <LoadingSkeleton count={2} />
        ) : !directory.isError && !isPocket && !reloadFailed ? (
          choices.length ? (
            <ScrollView style={{ maxHeight: height * 0.45 }}>
              {choices.map((candidate) => (
                <ActionButton
                  key={candidate.id}
                  label={t('savingsPocket.choose', { name: candidate.name })}
                  disabled={disabled || write.isPending}
                  onPress={() => void choose(account, candidate)}
                />
              ))}
            </ScrollView>
          ) : (
            <Text style={{ color: theme.textSecondary }}>{t('savingsPocket.empty')}</Text>
          )
        ) : null}
      </Dialog>
    ) : null;

  /** "Savings pocket: …" for the account detail, marked when the pocket is archived. */
  const currentLine =
    eligible && current ? (
      <Text style={{ color: theme.textSecondary }}>
        {t(
          current.status === 'ARCHIVED' ? 'savingsPocket.currentArchived' : 'savingsPocket.current',
          {
            name: current.name,
          },
        )}
      </Text>
    ) : null;

  return { eligible, trigger, dialog, currentLine, usablePocketName: usable?.name };
}

/**
 * The recovery on an import row warning `IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE`. Once a usable
 * pocket is linked it says that this import books the move there: the commit picks the pocket
 * when it runs, while the row's warning stays what the preview found.
 */
export function LinkPocket({ account, disabled }: { account?: Account; disabled: boolean }) {
  const intl = useIntl();
  const theme = useTheme();
  const pocket = useSavingsPocketLink({ account, disabled, context: 'import', load: true });
  return (
    <>
      {pocket.usablePocketName ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusGain }}>
          {'✓ '}
          {intl.formatMessage(
            { id: 'savingsPocket.thisImportBooks' },
            { name: pocket.usablePocketName },
          )}
        </Text>
      ) : null}
      {pocket.trigger()}
      {pocket.dialog}
    </>
  );
}
