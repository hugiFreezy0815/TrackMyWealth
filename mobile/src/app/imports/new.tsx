import { useRouter } from 'expo-router';
import { useIntl } from 'react-intl';

import { StatementImportScreen } from '@/features/statement-import/statement-screen';
import { ShellScreen } from '@/features/shell/screen';

export default function ImportRoute() {
  const intl = useIntl();
  const router = useRouter();
  return (
    <ShellScreen title={intl.formatMessage({ id: 'statement.title' })}>
      <StatementImportScreen
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
