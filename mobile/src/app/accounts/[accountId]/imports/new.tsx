import { useLocalSearchParams, useRouter } from 'expo-router';
import { useIntl } from 'react-intl';

import { StatementImportScreen } from '@/features/statement-import/statement-screen';
import { ShellScreen } from '@/features/shell/screen';

export default function AccountImportRoute() {
  const intl = useIntl();
  const router = useRouter();
  const { accountId, batchId } = useLocalSearchParams<{ accountId: string; batchId?: string }>();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'statement.title' })}>
      <StatementImportScreen
        accountId={accountId}
        initialBatchId={batchId}
        onExit={() => router.replace('/overview')}
        onShowAccount={(id) =>
          router.replace({
            pathname: '/accounts/[accountId]',
            params: { accountId: id },
          })
        }
      />
    </ShellScreen>
  );
}
