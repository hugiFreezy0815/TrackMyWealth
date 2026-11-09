import { useIdentity } from '@/features/auth/use-identity';
import { ReferenceDataScreen } from '@/features/reference-data/reference-data-screen';

/** Keep deep links protected if a session is restored, signed out or changes identity. */
export default function ReferenceDataRoute() {
  return <ReferenceDataScreen identity={useIdentity()} />;
}
