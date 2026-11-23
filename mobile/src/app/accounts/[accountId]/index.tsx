import { useRef, useState, type ComponentRef } from 'react';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { Platform, RefreshControl, useWindowDimensions, View, type Text } from 'react-native';
import { useIntl } from 'react-intl';
import { useAccount } from '@/api/queries/resources';
import { Spacing } from '@/constants/theme';
import { isAccessFailure } from '@/features/account-detail/access';
import { LiabilityDetail } from '@/features/liability-detail/liability-detail';
import { AccountDetail, useAccountRefresh } from '@/features/account-detail/account-detail';
import { usePanelFocusReturn } from '@/features/account-detail/panel-focus';
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
  const admin = isAdministrator(identity);
  const desktopAccount = useAccount(webPanel && !admin ? (accountId ?? '') : '');
  const accountUnavailable = isAccessFailure(desktopAccount.error);
  const summary = accountUnavailable ? undefined : desktopAccount.data;
  const desktopLiability =
    webPanel && summary && (summary.hasAmortisation || summary.hasStatementCycle);
  const [selection, setSelection] = useState<{ accountId: string; transactionId: string } | null>(
    null,
  );
  const selectedTransaction =
    webPanel && !accountUnavailable && selection?.accountId === accountId
      ? selection?.transactionId
      : undefined;
  const titleRef = useRef<ComponentRef<typeof Text>>(null);
  const panelFocus = usePanelFocusReturn(!!selectedTransaction, titleRef);

  if (admin) return <Redirect href={homeFor(true)} />;
  const selectTransaction = (transactionId: string) =>
    setSelection({ accountId: accountId ?? '', transactionId });
  const openTransaction = (transactionId: string, transactionAccountId = accountId ?? '') => {
    if (webPanel && transactionAccountId === accountId) {
      panelFocus.rememberOpener();
      selectTransaction(transactionId);
    } else {
      router.push({
        pathname: '/accounts/[accountId]/transactions/[transactionId]',
        params: { accountId: transactionAccountId, transactionId },
      });
    }
  };
  const closePanel = () => {
    panelFocus.returnFocusOnClose();
    setSelection(null);
  };
  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'account.heading' })}
      titleRef={webPanel ? titleRef : undefined}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
      detailWidth={400}
      virtualizedNative
      aside={
        selectedTransaction || desktopLiability ? (
          <View style={{ gap: Spacing.three }}>
            {selectedTransaction ? (
              <TransactionDetail
                key={selectedTransaction}
                accountId={accountId ?? ''}
                transactionId={selectedTransaction}
                onBack={closePanel}
                onRelatedTransaction={selectTransaction}
                onAccount={(id) =>
                  router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })
                }
              />
            ) : null}
            {desktopLiability ? (
              <LiabilityDetail key={summary.id} account={summary} onTransaction={openTransaction} />
            ) : null}
          </View>
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
        onRepaymentTransaction={openTransaction}
        liabilityInAside={webPanel}
        selectedTransactionId={selectedTransaction}
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
