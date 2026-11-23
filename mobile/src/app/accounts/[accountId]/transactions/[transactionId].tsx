import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { useIntl } from 'react-intl';

import {
  AccountDetailLayout,
  useDesktopAccountLayout,
} from '@/features/account-detail/account-detail-layout';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';
import { TransactionDetail } from '@/features/transaction-detail/transaction-detail';

export default function TransactionDetailRoute() {
  const {
    accountId = '',
    transactionId = '',
    repaymentFromAccountId,
  } = useLocalSearchParams<{
    accountId: string;
    transactionId: string;
    repaymentFromAccountId?: string | string[];
  }>();
  const repaymentOrigin =
    typeof repaymentFromAccountId === 'string' && repaymentFromAccountId
      ? repaymentFromAccountId
      : undefined;
  const parentAccountId = repaymentOrigin ?? accountId;
  const router = useRouter();
  const intl = useIntl();
  const identity = useIdentity();
  const desktop = useDesktopAccountLayout();
  const parent = () => {
    const href = {
      pathname: '/accounts/[accountId]' as const,
      params: { accountId: parentAccountId },
    };
    if (repaymentOrigin) router.dismissTo(href);
    else router.replace(href);
  };
  const openRelated = (id: string, transactionAccountId = accountId) => {
    const params = {
      accountId: transactionAccountId,
      transactionId: id,
      // Kept also for a row of the loan itself: Back then dismisses to the loan route below
      // instead of adding a second one.
      ...(repaymentOrigin ? { repaymentFromAccountId: repaymentOrigin } : {}),
    };
    // On desktop the account stays on screen, so the route keeps its screen: the side column's
    // repayment history and the opening row survive the URL change.
    if (desktop) router.setParams(params);
    else router.replace({ pathname: '/accounts/[accountId]/transactions/[transactionId]', params });
  };

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  if (desktop) {
    return (
      <AccountDetailLayout
        key={parentAccountId}
        accountId={parentAccountId}
        desktop
        linkedTransaction={{ accountId, transactionId }}
        onCloseLinkedTransaction={parent}
        onRelatedLinkedTransaction={openRelated}
      />
    );
  }
  return (
    <ShellScreen title={intl.formatMessage({ id: 'txn.heading' })}>
      <TransactionDetail
        key={JSON.stringify([accountId, transactionId])}
        accountId={accountId}
        transactionId={transactionId}
        onRelatedTransaction={openRelated}
        onBack={parent}
        onAccount={(id) =>
          router.push({ pathname: '/accounts/[accountId]', params: { accountId: id } })
        }
      />
    </ShellScreen>
  );
}
