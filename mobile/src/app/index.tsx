import { Redirect } from 'expo-router';

import { isAdministrator } from '@/features/auth/session';
import { useIdentity } from '@/features/auth/use-identity';
import { homeFor } from '@/features/shell/navigation';

export default function IndexRoute() {
  const identity = useIdentity();
  return <Redirect href={homeFor(isAdministrator(identity))} />;
}
