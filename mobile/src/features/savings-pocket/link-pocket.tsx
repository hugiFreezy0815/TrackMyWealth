import { useRef, useState } from 'react';
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

async function readDirectory(signal: AbortSignal) {
  const [active, archived] = await Promise.all([
    typedApi.get('/api/v1/accounts', { signal }),
    typedApi.get('/api/v1/accounts', { query: { archived: true }, signal }),
  ]);
  return [...active, ...archived];
}

export function LinkPocket({
  account,
  disabled,
  showCurrent = false,
}: {
  account?: Account;
  disabled: boolean;
  showCurrent?: boolean;
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
    enabled: eligible && !disabled,
    staleTime: 30_000,
  });
  const pocketId = showCurrent ? account?.savingsPocketAccountId : undefined;
  const currentPocket = useQuery({
    queryKey: queryKeys.account(pocketId ?? ''),
    queryFn: ({ signal }) =>
      typedApi.get('/api/v1/accounts/{accountId}', {
        params: { accountId: pocketId ?? '' },
        signal,
      }),
    enabled: !!pocketId,
  });
  const write = useMutation({
    mutationFn: ({ source, target }: { source: Account; target: AccountRow }) =>
      typedApi.put('/api/v1/accounts/{accountId}/savings-pocket', {
        params: { accountId: source.id },
        version: ifMatch(source),
        body: { savingsPocketAccountId: target.id },
      }),
  });

  if (!account || !eligible) return null;

  const close = () => {
    if (locked.current) return;
    setOpen(false);
    if (!reloadFailed) setError(undefined);
    setChanged(false);
  };
  const refresh = async () => {
    setReloading(true);
    try {
      const accountKey = queryKeys.account(account.id);
      await client.cancelQueries({ queryKey: accountKey, exact: true });
      // Bypass the fresh cache, and keep the last account visible if the GET fails.
      const latest = await typedApi.get('/api/v1/accounts/{accountId}', {
        params: { accountId: account.id },
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
  const choose = async (target: AccountRow) => {
    if (locked.current || disabled || reloading || reloadFailed) return;
    locked.current = true;
    setError(undefined);
    try {
      const updated = await write.mutateAsync({ source: account, target });
      // The API builds its strong ETag from this same version.
      await Promise.all([
        client.cancelQueries({ queryKey: queryKeys.account(account.id), exact: true }),
        client.cancelQueries({ queryKey: directoryKey, exact: true }),
      ]);
      client.setQueryData(queryKeys.account(account.id), updated);
      client.setQueryData<AccountRow[]>(directoryKey, (previous) =>
        previous?.map((item) =>
          item.id === account.id
            ? { ...item, savingsPocketAccountId: updated.savingsPocketAccountId }
            : item,
        ),
      );
      setOpen(false);
      setChanged(false);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 412) {
        await refresh();
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

  const isPocket = directory.data?.some((item) => item.savingsPocketAccountId === account.id);
  const current = currentPocket.data;
  const choices = directory.data ? pocketChoices(account, directory.data) : [];

  return (
    <>
      {showCurrent && current ? (
        <Text style={{ color: theme.textSecondary }}>
          {t('savingsPocket.current', { name: current.name })}
        </Text>
      ) : null}
      {directory.isError && !open ? (
        <ErrorBanner error={directory.error} onRetry={() => void directory.refetch()} />
      ) : null}
      {directory.isLoading && !open ? <LoadingSkeleton count={1} /> : null}
      {directory.data && !directory.isError && !isPocket ? (
        <ActionButton
          label={t('savingsPocket.link')}
          disabled={disabled}
          onPress={() => setOpen(true)}
        />
      ) : null}
      <Dialog
        visible={open}
        title={t('savingsPocket.link')}
        onClose={close}
        closeDisabled={write.isPending || reloading}
      >
        <Text style={{ color: theme.textSecondary }}>{t('savingsPocket.futureOnly')}</Text>
        {changed ? (
          <Text accessibilityRole="alert" style={{ color: theme.textPrimary }}>
            {t('savingsPocket.reloaded')}
          </Text>
        ) : null}
        {error ? (
          <ErrorBanner error={error} onRetry={reloadFailed ? () => void refresh() : undefined} />
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
                  onPress={() => void choose(candidate)}
                />
              ))}
            </ScrollView>
          ) : (
            <Text style={{ color: theme.textSecondary }}>{t('savingsPocket.empty')}</Text>
          )
        ) : null}
      </Dialog>
    </>
  );
}
