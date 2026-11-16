import { useLayoutEffect, useRef, useState, type ComponentRef } from 'react';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { Platform, RefreshControl, useWindowDimensions, type Text } from 'react-native';
import { useIntl } from 'react-intl';
import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';
import { AccountDetail, useAccountRefresh } from '@/features/account-detail/account-detail';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';

export default function AccountDetailRoute() {
  const { accountId, tab } = useLocalSearchParams<{ accountId: string; tab?: string | string[] }>();
  const initialTab = tab === 'positions' ? 'positions' : 'activity';
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  const { refreshing, refresh } = useAccountRefresh();
  const { width } = useWindowDimensions();
  const webPanel = Platform.OS === 'web' && width >= 1024;
  const [selectedTransaction, setSelectedTransaction] = useState<string | null>(null);
  const openingRow = useRef<HTMLElement | null>(null);
  const restoreFocus = useRef(false);
  const titleRef = useRef<ComponentRef<typeof Text>>(null);

  useLayoutEffect(() => {
    if (selectedTransaction !== null || !restoreFocus.current) return;
    restoreFocus.current = false;
    const opener = openingRow.current;
    openingRow.current = null;
    if (opener?.isConnected && document.activeElement !== opener) opener.focus();
    if (!opener || document.activeElement !== opener) moveAccessibilityFocus(titleRef.current);
  }, [selectedTransaction]);

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  const openTransaction = (transactionId: string) => {
    if (webPanel) {
      openingRow.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setSelectedTransaction(transactionId);
    } else {
      router.push({
        pathname: '/accounts/[accountId]/transactions/[transactionId]',
        params: { accountId: accountId ?? '', transactionId },
      });
    }
  };
  const closePanel = () => {
    restoreFocus.current = true;
    setSelectedTransaction(null);
  };
  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'account.heading' })}
      titleRef={webPanel ? titleRef : undefined}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
      detailWidth={400}
      virtualizedNative
      aside={
        webPanel && selectedTransaction ? (
          <TransactionDetail
            key={selectedTransaction}
            accountId={accountId ?? ''}
            transactionId={selectedTransaction}
            onBack={closePanel}
            onRelatedTransaction={setSelectedTransaction}
            onAccount={(id) =>
              router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })
            }
          />
        ) : undefined
      }
    >
      <AccountDetail
        key={JSON.stringify([accountId, initialTab])}
        initialTab={initialTab}
        accountId={accountId ?? ''}
        refreshing={refreshing}
        onRefresh={() => void refresh()}
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
