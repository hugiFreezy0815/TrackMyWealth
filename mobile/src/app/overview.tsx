import { Redirect } from 'expo-router';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';
import { ShellScreen } from '@/features/shell/screen';

/** #489 owns the data cards; the shell deliberately never invents financial totals. */
export default function OverviewRoute() {
  const intl = useIntl();
  const identity = useIdentity();
  // The administrator is never a workspace member and has no financial overview (PO 2026-11-09).
  if (isAdministrator(identity)) return <Redirect href={homeFor(true)} />;
  return (
    <ShellScreen title={intl.formatMessage({ id: 'nav.overview' })}>
      <ThemedText>{intl.formatMessage({ id: 'overview.pending' })}</ThemedText>
    </ShellScreen>
  );
}
