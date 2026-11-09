import { useState } from 'react';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { Platform, useWindowDimensions } from 'react-native';
import { useIntl } from 'react-intl';
import { AccountDetail } from '@/features/account-detail/account-detail';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';

export default function AccountDetailRoute() {
  const { accountId } = useLocalSearchParams<{ accountId: string }>();
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  const { width } = useWindowDimensions();
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);
  const webPanel = Platform.OS === 'web' && width >= 1024;

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  const openTransaction = (transactionId: string) => {
    if (webPanel) setSelectedTransaction(transactionId);
    else router.push({
      pathname: '/accounts/[accountId]/transactions/[transactionId]',
      params: { accountId: accountId ?? '', transactionId },
    });
  };
  return (
    <ShellScreen title={intl.formatMessage({ id: 'account.heading' })} detailWidth={400}
      aside={webPanel && selectedTransaction ? (
        <TransactionDetail accountId={accountId ?? ''} transactionId={selectedTransaction}
          onBack={() => setSelectedTransaction(null)}
          onAccount={(id) => router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })} />
      ) : undefined}>
      <AccountDetail accountId={accountId ?? ''}
        onBack={() => router.replace('/overview')}
        onTransaction={openTransaction}
        onImport={() => router.push({
          pathname: '/accounts/[accountId]/imports/new',
          params: { accountId: accountId ?? '' },
        })} />
    </ShellScreen>
  );
}
