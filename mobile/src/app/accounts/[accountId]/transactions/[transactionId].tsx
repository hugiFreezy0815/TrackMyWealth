import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { useIntl } from 'react-intl';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';
import { ShellScreen } from '@/features/shell/screen';
import { homeFor } from '@/features/shell/navigation';

export default function TransactionDetailRoute() {
  const { accountId, transactionId } = useLocalSearchParams<{
    accountId: string; transactionId: string;
  }>();
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  return (
    <ShellScreen title={intl.formatMessage({ id: 'txn.heading' })}>
      <TransactionDetail accountId={accountId ?? ''} transactionId={transactionId ?? ''}
        onBack={() => router.replace({ pathname: '/accounts/[accountId]', params: { accountId: accountId ?? '' } })}
        onAccount={(id) => router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })} />
    </ShellScreen>
  );
}
