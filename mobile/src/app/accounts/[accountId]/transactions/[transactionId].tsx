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
  const openRelated = (id: string, transactionAccountId = accountId) =>
    router.replace({
      pathname: '/accounts/[accountId]/transactions/[transactionId]',
      params: {
        accountId: transactionAccountId,
        transactionId: id,
        // A row of the loan itself has the loan as its parent and needs no repayment origin.
        ...(repaymentOrigin && transactionAccountId !== repaymentOrigin
          ? { repaymentFromAccountId: repaymentOrigin }
          : {}),
      },
    });

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
