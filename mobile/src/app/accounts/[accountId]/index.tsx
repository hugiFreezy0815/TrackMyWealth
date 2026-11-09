import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { useIntl } from 'react-intl';
import { RefreshControl } from 'react-native';
import { AccountDetail, useAccountRefresh } from '@/features/account-detail/account-detail';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';

export default function AccountDetailRoute() {
  const { accountId } = useLocalSearchParams<{ accountId: string }>();
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  const { refreshing, refresh } = useAccountRefresh();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'account.heading' })}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
    >
      <AccountDetail
        accountId={accountId ?? ''}
        onBack={() => router.replace('/overview')}
        onTransaction={(transactionId) =>
          router.push({
            pathname: '/accounts/[accountId]/transactions/[transactionId]',
            params: { accountId: accountId ?? '', transactionId },
          })
        }
        onImport={() =>
          router.push({
            pathname: '/accounts/[accountId]/imports/new',
            params: { accountId: accountId ?? '' },
          })
        }
      />
    </ShellScreen>
  );
}
