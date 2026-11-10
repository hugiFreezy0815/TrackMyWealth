import { ActivityIndicator, Text, View } from 'react-native';

import { useAccounts, usePortfolios } from '@/api/queries/resources';
import {
  ActionButton,
  ChipGroup,
  EmptyState,
  ErrorBanner,
  LoadingSkeleton,
  SectionCard,
} from '@/components/shared';
import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { useText } from './statement-text';
import type { StatementImport } from './use-statement-import';

/**
 * The web overview entry (no fixed account): the accounts that keep a transaction ledger, as
 * "{portfolio} · {account}".
 */
export function AccountPicker({ flow }: { flow: StatementImport }) {
  const t = useText();
  const theme = useTheme();
  const accounts = useAccounts();
  const options = (accounts.data ?? [])
    .filter((item) => !item.archived && item.hasTransactions)
    .map((item) => ({ value: item.id, label: `${item.portfolioName} · ${item.name}` }));
  return (
    <SectionCard title={t('statement.account.select')}>
      {accounts.isPending ? <LoadingSkeleton count={3} /> : null}
      {accounts.isError ? (
        <ErrorBanner error={accounts.error} onRetry={() => void accounts.refetch()} />
      ) : null}
      {accounts.isSuccess && options.length === 0 ? (
        <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
          {t('statement.account.none')}
        </Text>
      ) : null}
      <ChipGroup
        label={t('statement.account.select')}
        selected={flow.accountId ? [flow.accountId] : []}
        options={options}
        disabled={flow.busy}
        onSelect={flow.selectAccount}
      />
    </SectionCard>
  );
}

/** "Import into": "{portfolio} · {account}", "{type} · {currency}". */
export function AccountLine({ flow }: { flow: StatementImport }) {
  const t = useText();
  const theme = useTheme();
  const portfolios = usePortfolios();
  const account = flow.account.data;
  if (!account) return null;
  const portfolio = portfolios.data?.find((candidate) => candidate.id === account.portfolioId);
  return (
    <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
      {t('statement.account.into', {
        name: portfolio ? `${portfolio.name} · ${account.name}` : account.name,
      })}
      {` · ${account.accountType} · ${account.nativeCurrency}`}
    </Text>
  );
}

/** Step 1 (M08): the file, then the format when the server could not pick one. */
export function FileStep({ flow }: { flow: StatementImport }) {
  const t = useText();
  const theme = useTheme();
  const { batch } = flow;
  return (
    <SectionCard title={t('statement.file.title')}>
      {!batch ? (
        <>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
            {t('statement.file.supported')}
          </Text>
          <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
            {t('statement.file.max')}
          </Text>
          {flow.uploading ? (
            <View
              accessibilityRole="progressbar"
              aria-busy
              accessibilityLabel={t('statement.file.reading', { name: flow.file?.name ?? '' })}
              style={{ gap: Spacing.two }}
            >
              <ActivityIndicator />
              <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
                {t('statement.file.reading', { name: flow.file?.name ?? '' })}
              </Text>
              {flow.slowUpload ? (
                <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>
                  {t('statement.file.slow')}
                </Text>
              ) : null}
              <ActionButton label={t('statement.file.cancelUpload')} onPress={flow.cancelUpload} />
            </View>
          ) : (
            <ActionButton
              label={t('statement.file.pick')}
              disabled={!flow.canUpload || !flow.accountId}
              onPress={() => void flow.chooseFile()}
            />
          )}
          {flow.fileError ? <ErrorBanner message={t(`statement.file.${flow.fileError}`)} /> : null}
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
            {t('statement.nothingSaved')}
          </Text>
        </>
      ) : (
        <>
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
            {batch.sourceFileName}
          </Text>
          {batch.templateCandidates.length ? (
            <>
              <ChipGroup
                label={t('statement.template.choose')}
                selected={[flow.selectedTemplate || batch.templateCandidates[0].template.id]}
                options={batch.templateCandidates.map((candidate) => ({
                  value: candidate.template.id,
                  label: `${candidate.template.name} · ${candidate.match}`,
                }))}
                disabled={flow.busy}
                onSelect={flow.selectTemplate}
              />
              <ActionButton
                label={t('statement.template.parse')}
                disabled={!flow.online || flow.busy}
                onPress={flow.parse}
              />
            </>
          ) : (
            <EmptyState
              title={t('statement.template.missing')}
              description={t('statement.template.report')}
            />
          )}
        </>
      )}
    </SectionCard>
  );
}
