import { Redirect, useRouter, type Href } from 'expo-router';
import { useIntl } from 'react-intl';

import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { OverviewScreen } from '@/features/overview/overview-screen';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';

/** Keep the merged #487 auth guard, app shell and persisted privacy toggle. */
export default function OverviewRoute() {
  const intl = useIntl();
  const identity = useIdentity();
  const router = useRouter();
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;

  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.overview' })}>
      <OverviewScreen onAddPortfolio={() => router.push('/portfolios/new' as Href)} />
    </ShellScreen>
  );
}
