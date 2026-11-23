import { Redirect, useLocalSearchParams } from 'expo-router';

import {
  AccountDetailLayout,
  useDesktopAccountLayout,
} from '@/features/account-detail/account-detail-layout';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';

export default function AccountDetailRoute() {
  const { accountId = '', tab } = useLocalSearchParams<{
    accountId: string;
    tab?: string | string[];
  }>();
  const initialTab = tab === 'positions' ? 'positions' : 'activity';
  const desktop = useDesktopAccountLayout();
  const identity = useIdentity();

  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  return (
    <AccountDetailLayout
      key={JSON.stringify([accountId, initialTab])}
      accountId={accountId}
      initialTab={initialTab}
      desktop={desktop}
    />
  );
}
