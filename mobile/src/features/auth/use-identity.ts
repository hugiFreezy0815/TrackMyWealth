import { useEffect, useState } from 'react';

import { type Identity, sessionSnapshot, subscribeSession } from './session';

/** The server-verified identity; follows restore, sign-out and a change of member. */
export function useIdentity(): Identity | null {
  const [identity, setIdentity] = useState(() => sessionSnapshot().identity);
  useEffect(() => subscribeSession(() => setIdentity(sessionSnapshot().identity)), []);
  return identity;
}
