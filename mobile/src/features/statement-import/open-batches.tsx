import { Text, View } from 'react-native';

import { ActionButton, ErrorBanner, LoadingSkeleton, SectionCard } from '@/components/shared';
import { Spacing } from '@/constants/theme';
import { formatDateTime } from '@/format/date';
import { useTheme } from '@/hooks/use-theme';
import { useIntl } from 'react-intl';

import { isOpenBatch } from './model';
import { useText } from './statement-text';
import type { StatementImport } from './use-statement-import';

/**
 * The account's unfinished imports (UPLOADED or PARSED), newest first: at most 20, so all are on
 * the first page (IMPORT_TOO_MANY_OPEN_BATCHES).
 */
export function OpenBatches({ flow }: { flow: StatementImport }) {
  const intl = useIntl();
  const t = useText();
  const theme = useTheme();
  const { openBatches } = flow;
  return (
    <SectionCard title={t('statement.open.title')}>
      {openBatches.isPending ? <LoadingSkeleton count={2} /> : null}
      {openBatches.isError ? (
        <ErrorBanner error={openBatches.error} onRetry={() => void openBatches.refetch()} />
      ) : null}
      {openBatches.data?.content.filter(isOpenBatch).map((item) => (
        <View key={item.id} style={{ gap: Spacing.one }}>
          <Text style={{ color: theme.textPrimary }}>
            {item.sourceFileName ?? t('statement.file.title')}
          </Text>
          <Text style={{ color: theme.textSecondary }}>
            {formatDateTime(item.uploadedAt, intl.locale)}
          </Text>
          <ActionButton label={t('statement.open.resume')} onPress={() => flow.resume(item)} />
          <ActionButton
            label={t('statement.discard')}
            disabled={!flow.online || flow.busy}
            onPress={() => flow.discardOpen(item)}
          />
        </View>
      ))}
    </SectionCard>
  );
}
