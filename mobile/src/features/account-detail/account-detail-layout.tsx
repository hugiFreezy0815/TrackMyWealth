import { useRef, useState, type ComponentRef } from 'react';
import { useRouter } from 'expo-router';
import { useIntl } from 'react-intl';
import { Platform, RefreshControl, useWindowDimensions, View, type Text } from 'react-native';

import { useAccount } from '@/api/queries/resources';
import { Spacing } from '@/constants/theme';
import { LiabilityDetail } from '@/features/liability-detail/liability-detail';
import { layoutFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';

import { isAccessFailure } from './access';
import { AccountDetail, useAccountRefresh, type AccountDetailProps } from './account-detail';
import { usePanelFocusReturn } from './panel-focus';

type TransactionSelection = { accountId: string; transactionId: string };

export function useDesktopAccountLayout() {
  const { width } = useWindowDimensions();
  return Platform.OS === 'web' && layoutFor(width) === 'desktop';
}

export function AccountDetailLayout({
  accountId,
  initialTab,
  desktop,
  linkedTransaction,
  onCloseLinkedTransaction,
  onRelatedLinkedTransaction,
}: {
  accountId: string;
  initialTab?: AccountDetailProps['initialTab'];
  desktop: boolean;
  linkedTransaction?: TransactionSelection;
  onCloseLinkedTransaction?: () => void;
  onRelatedLinkedTransaction?: (transactionId: string) => void;
}) {
  const router = useRouter();
  const intl = useIntl();
  const { refreshing, refresh } = useAccountRefresh();
  const desktopAccount = useAccount(desktop ? accountId : '');
  const accountUnavailable = isAccessFailure(desktopAccount.error);
  const summary = accountUnavailable ? undefined : desktopAccount.data;
  const hasLiabilityPanels =
    desktop && summary && (summary.hasAmortisation || summary.hasStatementCycle);
  const [selection, setSelection] = useState<TransactionSelection | null>(null);
  const selected = desktop && !accountUnavailable ? (selection ?? linkedTransaction) : undefined;
  const titleRef = useRef<ComponentRef<typeof Text>>(null);
  const panelFocus = usePanelFocusReturn(!!selection && !!selected, titleRef);

  const showTransaction = (
    transactionId: string,
    transactionAccountId: string,
    repayment: boolean,
  ) => {
    if (desktop) {
      panelFocus.rememberOpener();
      setSelection({ accountId: transactionAccountId, transactionId });
    } else {
      router.push({
        pathname: '/accounts/[accountId]/transactions/[transactionId]',
        params: {
          accountId: transactionAccountId,
          transactionId,
          ...(repayment ? { repaymentFromAccountId: accountId } : {}),
        },
      });
    }
  };
  const openActivity = (transactionId: string) => {
    if (desktop && !selection && linkedTransaction?.accountId === accountId) {
      onRelatedLinkedTransaction?.(transactionId);
    } else {
      showTransaction(transactionId, accountId, false);
    }
  };
  const openRepayment = (transactionId: string, transactionAccountId: string) =>
    showTransaction(transactionId, transactionAccountId, true);
  const closeTransaction = () => {
    if (selection) {
      panelFocus.returnFocusOnClose();
      setSelection(null);
    } else {
      onCloseLinkedTransaction?.();
    }
  };

  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'account.heading' })}
      titleRef={desktop ? titleRef : undefined}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
      detailWidth={400}
      virtualizedNative
      aside={
        selected || hasLiabilityPanels ? (
          <View style={{ gap: Spacing.three }}>
            {selected ? (
              <TransactionDetail
                key={JSON.stringify([selected.accountId, selected.transactionId])}
                accountId={selected.accountId}
                transactionId={selected.transactionId}
                onBack={closeTransaction}
                onRelatedTransaction={(transactionId) => {
                  if (selection) setSelection({ ...selection, transactionId });
                  else onRelatedLinkedTransaction?.(transactionId);
                }}
                onAccount={(id) =>
                  router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })
                }
              />
            ) : null}
            {hasLiabilityPanels ? (
              <LiabilityDetail account={summary} onTransaction={openRepayment} />
            ) : null}
          </View>
        ) : undefined
      }
    >
      <AccountDetail
        accountId={accountId}
        initialTab={initialTab}
        refreshing={refreshing}
        onRefresh={() => void refresh()}
        onBack={() => router.replace('/overview')}
        onTransaction={openActivity}
        onRepaymentTransaction={openRepayment}
        liabilityInAside={desktop}
        selectedTransactionId={selected?.accountId === accountId ? selected.transactionId : null}
        onImport={() =>
          router.push({
            pathname: '/accounts/[accountId]/imports/new',
            params: { accountId },
          })
        }
      />
    </ShellScreen>
  );
}
