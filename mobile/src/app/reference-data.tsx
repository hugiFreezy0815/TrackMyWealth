import { useEffect, useState } from 'react';

import { ReferenceDataScreen } from '@/features/reference-data/reference-data-screen';
import { sessionSnapshot, subscribeSession } from '@/features/auth/session';

/** Keep deep links protected if a session is restored, signed out or changes identity. */
export default function ReferenceDataRoute() {
  const [identity, setIdentity] = useState(() => sessionSnapshot().identity);

  useEffect(
    () => subscribeSession(() => setIdentity(sessionSnapshot().identity)),
    [],
  );

  return <ReferenceDataScreen identity={identity} />;
}
