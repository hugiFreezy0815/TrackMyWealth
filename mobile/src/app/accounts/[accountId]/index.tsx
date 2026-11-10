import { useRef, useState } from 'react';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { Platform, RefreshControl, useWindowDimensions } from 'react-native';
import { useIntl } from 'react-intl';
import { AccountDetail, useAccountRefresh } from '@/features/account-detail/account-detail';
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
  const { refreshing, refresh } = useAccountRefresh();
  const { width } = useWindowDimensions();
  const webPanel = Platform.OS === 'web' && width >= 1024;
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);
  const previousFocus = useRef<HTMLElement | null>(null);

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  const openTransaction = (transactionId: string) => {
    if (webPanel) {
      if (!selectedTransaction) previousFocus.current = document.activeElement as HTMLElement | null;
      setSelectedTransaction(transactionId);
    } else {
      router.push({
        pathname: '/accounts/[accountId]/transactions/[transactionId]',
        params: { accountId: accountId ?? '', transactionId },
      });
    }
  };
  const closePanel = () => {
    setSelectedTransaction(null);
    previousFocus.current?.focus();
    previousFocus.current = null;
  };
  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'account.heading' })}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
      detailWidth={400}
      aside={
        webPanel && selectedTransaction ? (
          <TransactionDetail
            key={selectedTransaction}
            accountId={accountId ?? ''}
            transactionId={selectedTransaction}
            onBack={closePanel}
            onRelatedTransaction={openTransaction}
            onAccount={(id) =>
              router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })
            }
          />
        ) : undefined
      }
    >
      <AccountDetail
        accountId={accountId ?? ''}
        onBack={() => router.replace('/overview')}
        onTransaction={openTransaction}
        selectedTransactionId={webPanel ? selectedTransaction : null}
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
