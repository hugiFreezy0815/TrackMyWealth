import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { Platform, useWindowDimensions } from 'react-native';
import { useIntl } from 'react-intl';
import { AccountDetail } from '@/features/account-detail/account-detail';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';
import { ShellScreen } from '@/features/shell/screen';
import { homeFor } from '@/features/shell/navigation';

export default function TransactionDetailRoute() {
  const { accountId, transactionId } = useLocalSearchParams<{
    accountId: string;
    transactionId: string;
  }>();
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  const { width } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1024;
  const parent = () =>
    router.replace({
      pathname: '/accounts/[accountId]',
      params: { accountId: accountId ?? '' },
    });
  const openTransaction = (id: string) =>
    router.replace({
      pathname: '/accounts/[accountId]/transactions/[transactionId]',
      params: { accountId: accountId ?? '', transactionId: id },
    });
  const detail = (
    <TransactionDetail
      key={transactionId ?? ''}
      accountId={accountId ?? ''}
      transactionId={transactionId ?? ''}
      onRelatedTransaction={openTransaction}
      onBack={parent}
      onAccount={(id) =>
        router.push({
          pathname: '/accounts/[accountId]',
          params: { accountId: id },
        })
      }
    />
  );

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  if (desktop)
    return (
      <ShellScreen
        title={intl.formatMessage({ id: 'account.heading' })}
        detailWidth={400}
        aside={detail}
      >
        <AccountDetail
          accountId={accountId ?? ''}
          onBack={() => router.replace('/overview')}
          selectedTransactionId={transactionId}
          onTransaction={openTransaction}
          onImport={() =>
            router.push({
              pathname: '/accounts/[accountId]/imports/new',
              params: { accountId: accountId ?? '' },
            })
          }
        />
      </ShellScreen>
    );
  return <ShellScreen title={intl.formatMessage({ id: 'txn.heading' })}>{detail}</ShellScreen>;
}
