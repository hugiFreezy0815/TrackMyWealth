import { useIntl } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import {
  ActionButton,
  Amount,
  ChipGroup,
  CountTile,
  ErrorBanner,
  LoadingSkeleton,
  SectionCard,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { formatDate, formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { usePortfolios } from '@/api/queries/resources';

import { useImportRows, useImportTemplate } from './api';
import {
  canCommit,
  FILTERS,
  filterCount,
  needsAccountConfirmation,
  statementPeriod,
  type Batch,
  type ImportRow,
  type RowFilter,
} from './model';
import { StatementRow } from './statement-row';
import { useText } from './statement-text';
import type { StatementImport } from './use-statement-import';

const UNREADABLE_BALANCE: readonly string[] = ['UNREADABLE', 'AMBIGUOUS', 'OUT_OF_RANGE'];

/** Tiles: New, duplicates and errors always; Skipped only when there are any. */
function showsTile(batch: Batch, filter: RowFilter): boolean {
  return filter !== 'ALL' && (filter !== 'SKIPPED' || batch.counts.skipped > 0);
}

/** "Read as {templateName}", or "CAMT bank statement" for a CAMT file (no template). */
function useFormatName(batch: Batch): string | null {
  const t = useText();
  const template = useImportTemplate(batch.templateId);
  if (template.data) return t('statement.template.readAs', { name: template.data.name });
  if (!batch.templateId && batch.sourceKind === 'DOCUMENT') return t('statement.template.camt');
  return null;
}

function Warnings({ flow, batch }: { flow: StatementImport; batch: Batch }) {
  const intl = useIntl();
  const t = useText();
  const theme = useTheme();
  const balance = batch.statementBalance;
  const period = statementPeriod(batch);
  const format = useFormatName(batch);
  const account = flow.account.data;
  const portfolios = usePortfolios();
  const portfolio = portfolios.data?.find((candidate) => candidate.id === account?.portfolioId);
  return (
    <SectionCard title={t('statement.review.title')}>
      <Text style={{ color: theme.textSecondary }}>
        {[portfolio?.name, account?.name, format].filter(Boolean).join(' · ')}
      </Text>
      <Text style={{ color: theme.textSecondary }}>
        {batch.sourceFileName}
        {period
          ? ` · ${t('statement.review.period', {
              from: formatDate(period.from, intl.locale),
              to: formatDate(period.to, intl.locale),
            })}`
          : ''}
      </Text>
      {batch.sameFileImportedIn ? (
        <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
          {'⚠ '}
          {t('statement.sameFile', {
            date: formatDateTime(batch.sameFileImportedIn.committedAt, intl.locale),
          })}
        </Text>
      ) : null}
      {needsAccountConfirmation(batch) ? (
        <View style={{ gap: Spacing.two }}>
          <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
            {'⚠ '}
            {t(
              batch.accountIdentification?.outcome === 'OTHER_ACCOUNT'
                ? 'statement.account.other'
                : 'statement.account.confirmPrompt',
            )}
          </Text>
          <ActionButton
            label={t('statement.account.confirm')}
            disabled={!flow.online || flow.busy}
            onPress={flow.confirmAccount}
          />
        </View>
      ) : null}
      {balance?.outcome === 'FOUND' && balance.balance != null ? (
        <View style={{ gap: Spacing.one }}>
          <Text style={{ color: theme.textPrimary }}>
            {t('statement.balance.found', {
              date: balance.date ? formatDate(balance.date, intl.locale) : '',
            })}
          </Text>
          <Amount
            value={balance.balance}
            currency={balance.currency ?? account?.nativeCurrency ?? ''}
          />
          {balance.snapshot === 'ALREADY_RECORDED' ? (
            <Text style={{ color: theme.textSecondary }}>{t('statement.balance.recorded')}</Text>
          ) : null}
          {balance.snapshot === 'CONFLICTING' ? (
            <Text accessibilityRole="alert" style={{ color: theme.statusWarning }}>
              {'⚠ '}
              {t('statement.balance.conflict', {
                file: balance.existingSnapshot?.importFileName ?? '',
              })}
            </Text>
          ) : null}
        </View>
      ) : null}
      {balance && UNREADABLE_BALANCE.includes(balance.outcome) ? (
        <Text style={{ color: theme.textSecondary }}>
          {balance.explanation ?? t('statement.balance.unreadable')}
        </Text>
      ) : null}
    </SectionCard>
  );
}

function Counts({ flow, batch }: { flow: StatementImport; batch: Batch }) {
  const t = useText();
  return (
    <SectionCard title={t('statement.counts.title')}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>
        {FILTERS.filter((item) => showsTile(batch, item)).map((item) => (
          <Pressable
            key={item}
            accessibilityRole="button"
            accessibilityLabel={`${t(`statement.filter.${item}`)} ${filterCount(batch, item)}`}
            onPress={() => flow.setFilter(item)}
          >
            <CountTile label={t(`statement.filter.${item}`)} count={filterCount(batch, item)} />
          </Pressable>
        ))}
        {batch.counts.warnings > 0 ? (
          <CountTile label={t('statement.counts.warnings')} count={batch.counts.warnings} />
        ) : null}
      </View>
      <ChipGroup
        label={t('statement.filter.label')}
        selected={[flow.filter]}
        options={FILTERS.map((value) => ({
          value,
          label: `${t(`statement.filter.${value}`)} ${filterCount(batch, value)}`,
        }))}
        onSelect={(value) => flow.setFilter(value as RowFilter)}
      />
    </SectionCard>
  );
}

function Rows({
  flow,
  batch,
  rows,
}: {
  flow: StatementImport;
  batch: Batch;
  rows: ReturnType<typeof useImportRows>;
}) {
  const t = useText();
  const loaded: ImportRow[] = rows.data?.pages.flatMap((page) => page.content) ?? [];
  return (
    <SectionCard title={t('statement.rows.title')}>
      {rows.isPending ? <LoadingSkeleton count={4} /> : null}
      {rows.isError ? <ErrorBanner error={rows.error} onRetry={() => void rows.refetch()} /> : null}
      {loaded.map((row) => (
        <StatementRow
          key={row.rowNumber}
          row={row}
          disabled={!flow.online || flow.busy}
          onSwitch={flow.switchRow}
        />
      ))}
      {rows.hasNextPage ? (
        <ActionButton
          label={t('statement.rows.more')}
          disabled={rows.isFetchingNextPage}
          onPress={() => void rows.fetchNextPage()}
        />
      ) : null}
      {batch.counts.errors > 0 ? (
        <ActionButton
          label={t('statement.errors.download')}
          onPress={() => void flow.downloadErrors(t('statement.errors.download'))}
        />
      ) : null}
    </SectionCard>
  );
}

/** Step 2 (M09 / W05). Nothing is counted on the device: every figure is the server's. */
export function ReviewStep({
  flow,
  batch,
  rows,
  unknownRow,
}: {
  flow: StatementImport;
  batch: Batch;
  rows: ReturnType<typeof useImportRows>;
  unknownRow: boolean;
}) {
  const t = useText();
  const theme = useTheme();
  return (
    <>
      <Warnings flow={flow} batch={batch} />
      <Counts flow={flow} batch={batch} />
      <Rows flow={flow} batch={batch} rows={rows} />
      <SectionCard title={t('statement.commit.title')}>
        <ActionButton
          label={t('statement.commit', { count: batch.counts.included })}
          disabled={!canCommit(batch, flow.busy, flow.online) || unknownRow}
          onPress={() => flow.commit(unknownRow)}
        />
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
          {t('statement.commit.rollback')}
        </Text>
      </SectionCard>
    </>
  );
}
