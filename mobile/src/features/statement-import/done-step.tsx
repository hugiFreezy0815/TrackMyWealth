import { Text } from 'react-native';

import { ActionButton, SectionCard } from '@/components/shared';
import { useTheme } from '@/hooks/use-theme';

import type { Batch } from './model';
import { useText } from './statement-text';
import type { StatementImport } from './use-statement-import';

const SAVED_BALANCE: readonly string[] = ['RECORDED', 'COMPLETED'];

/** Step 3: what was imported; S1, S2 and S3 were invalidated by the commit. */
export function DoneStep({
  flow,
  batch,
  onShowAccount,
}: {
  flow: StatementImport;
  batch: Batch;
  onShowAccount: (accountId: string) => void;
}) {
  const t = useText();
  const theme = useTheme();
  const snapshot = batch.statementBalance?.snapshot;
  return (
    <SectionCard title={t('statement.done.title')}>
      <Text accessibilityRole="header" style={{ color: theme.textPrimary }}>
        {t('statement.done.count', {
          count: batch.counts.imported,
          account: flow.account.data?.name ?? '',
        })}
      </Text>
      {snapshot && SAVED_BALANCE.includes(snapshot) ? (
        <Text style={{ color: theme.textSecondary }}>{t('statement.done.balance')}</Text>
      ) : null}
      <ActionButton
        label={t('statement.done.showAccount')}
        onPress={() => onShowAccount(flow.accountId)}
      />
      <ActionButton label={t('statement.done.again')} onPress={flow.startAgain} />
    </SectionCard>
  );
}
