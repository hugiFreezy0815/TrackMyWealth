import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';

import {
  ActionButton,
  ConflictDialog,
  Dialog,
  ErrorBanner,
  LoadingSkeleton,
  OfflineBanner,
  SectionCard,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { useIntl } from 'react-intl';

import { useImportRows } from './api';
import { DoneStep } from './done-step';
import { AccountLine, AccountPicker, FileStep } from './file-step';
import {
  importErrorMessageKey,
  isKnownBatchStatus,
  isKnownRowStatus,
  isRetryableUploadError,
  isUnsupportedHoldings,
  reviewableBatch,
} from './model';
import { OpenBatches } from './open-batches';
import { ReviewStep } from './review-step';
import { useText } from './statement-text';
import { useStatementImport, type StatementImport } from './use-statement-import';

type Props = {
  accountId?: string;
  initialBatchId?: string;
  onShowAccount: (id: string) => void;
  onExit: () => void;
};

/** Whole seconds until `at` (epoch ms), ticking down; 0 once it has passed or with no time. */
function useSecondsUntil(at: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (at === null) return;
    const tick = () => {
      setNow(Date.now());
      if (Date.now() >= at) clearInterval(timer);
    };
    const first = setTimeout(tick, 0);
    const timer = setInterval(tick, 1000);
    return () => {
      clearTimeout(first);
      clearInterval(timer);
    };
  }, [at]);
  return at === null ? 0 : Math.max(0, Math.ceil((at - now) / 1000));
}

/**
 * The write's failure, with its catalogue text. A busy parser (`RETRY_LATER`) offers Retry once
 * its Retry-After has passed and counts down until then.
 */
function FlowError({ flow }: { flow: StatementImport }) {
  const intl = useIntl();
  const t = useText();
  const theme = useTheme();
  const wait = useSecondsUntil(flow.retryAt);
  if (!flow.error) return null;
  const key = importErrorMessageKey(flow.error);
  const retryable = isRetryableUploadError(flow.error) && flow.file !== null && flow.online;
  return (
    <View>
      <ErrorBanner
        error={flow.error}
        message={key ? intl.formatMessage({ id: key }) : undefined}
        onRetry={retryable && wait === 0 ? flow.retryUpload : undefined}
      />
      {retryable && wait > 0 ? (
        <Text
          accessibilityLiveRegion="polite"
          style={{ ...TypeScale.caption, color: theme.textSecondary }}
        >
          {t('statement.retryIn', { seconds: wait })}
        </Text>
      ) : null}
    </View>
  );
}

/** S7: one account, one authoritative server batch and serial If-Match writes. */
export function StatementImportScreen({
  accountId: fixedAccountId,
  initialBatchId,
  onShowAccount,
  onExit,
}: Props) {
  const t = useText();
  const theme = useTheme();
  const flow = useStatementImport({ fixedAccountId, initialBatchId, onExit });
  const { batch } = flow;
  const reviewable = reviewableBatch(batch);
  const rows = useImportRows(flow.accountId, reviewable ? flow.batchId : '', flow.filter);
  const loadedRows = rows.data?.pages.flatMap((page) => page.content) ?? [];
  const unknownRow = loadedRows.some((row) => !isKnownRowStatus(row.status));
  const unknown = batch != null && !isKnownBatchStatus(batch.status);
  const holdings = batch != null && isUnsupportedHoldings(batch);
  const isDone = batch?.status === 'COMMITTED';
  const step = isDone ? 3 : reviewable ? 2 : 1;

  return (
    <View style={{ gap: Spacing.three, width: '100%', maxWidth: 900, alignSelf: 'center' }}>
      {/* The shell (#487) renders the page heading "Import statement"; the screen adds the step. */}
      <Text
        accessibilityRole="header"
        style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}
      >
        {t('statement.step', { current: step, title: t(`statement.step.${step}`) })}
      </Text>
      {!flow.online ? <OfflineBanner /> : null}
      {!flow.fixedAccount && !batch ? <AccountPicker flow={flow} /> : null}
      <AccountLine flow={flow} />
      {flow.account.isPending && flow.accountId ? <LoadingSkeleton count={1} /> : null}
      {flow.account.isError ? (
        <ErrorBanner error={flow.account.error} onRetry={() => void flow.account.refetch()} />
      ) : null}
      {flow.account.data && !flow.account.data.hasTransactions ? (
        <ErrorBanner message={t('statement.account.notSupported')} />
      ) : null}
      {flow.loaded.isError && !batch ? (
        <ErrorBanner error={flow.loaded.error} onRetry={() => void flow.loaded.refetch()} />
      ) : null}
      <FlowError flow={flow} />
      {unknown || unknownRow ? <ErrorBanner message={t('statement.updateRequired')} /> : null}
      {holdings ? (
        <SectionCard title={t('statement.holdings.title')}>
          <Text style={{ color: theme.textPrimary }}>{t('statement.holdings.soon')}</Text>
          <ActionButton
            label={t('statement.discard')}
            disabled={!flow.online || flow.busy}
            onPress={flow.askDiscard}
          />
        </SectionCard>
      ) : null}
      {(!batch || batch.status === 'UPLOADED') && !unknown && !holdings ? (
        <FileStep flow={flow} />
      ) : null}
      {batch && reviewable ? (
        <ReviewStep flow={flow} batch={batch} rows={rows} unknownRow={unknownRow} />
      ) : null}
      {batch && isDone ? (
        <DoneStep flow={flow} batch={batch} onShowAccount={onShowAccount} />
      ) : null}
      {flow.accountId && !batch && !flow.uploading ? (
        <ActionButton label={t('statement.open.continue')} onPress={flow.toggleOpenBatches} />
      ) : null}
      {flow.showOpen && flow.accountId ? <OpenBatches flow={flow} /> : null}
      {!isDone ? (
        <ActionButton
          label={t('statement.cancel')}
          disabled={flow.busy}
          onPress={batch ? flow.askDiscard : onExit}
        />
      ) : null}
      <Dialog
        title={t('statement.cancel.confirmTitle')}
        visible={flow.dialog === 'discard'}
        onClose={flow.closeDialog}
      >
        <Text style={{ color: theme.textPrimary }}>{t('statement.cancel.confirmBody')}</Text>
        <ActionButton
          label={t('statement.discard')}
          disabled={!flow.online || flow.busy}
          onPress={() => void flow.discard()}
        />
      </Dialog>
      <ConflictDialog
        visible={flow.dialog === 'conflict'}
        onClose={flow.closeDialog}
        onReview={flow.reviewConflict}
        onDiscard={flow.askDiscard}
      />
    </View>
  );
}
