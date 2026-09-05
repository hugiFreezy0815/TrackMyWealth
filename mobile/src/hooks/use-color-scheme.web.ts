import { useEffect, useState } from 'react';
import { useColorScheme as useRNColorScheme } from 'react-native';

/**
 * To support static rendering, this value needs to be re-calculated on the client side for web
 */
export function useColorScheme() {
  const [hasHydrated, setHasHydrated] = useState(false);

  useEffect(() => {
    // Deliberate hydration-detection flag, not derived state: `useColorScheme` is a client-only
    // API on web, so this effect exists solely to trigger a second render once we're known to be
    // on the client, avoiding a static-rendering/hydration mismatch - the standard shape for this
    // exact pattern, not the accidental-derived-state case react-hooks/set-state-in-effect targets.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHasHydrated(true);
  }, []);

  const colorScheme = useRNColorScheme();

  if (hasHydrated) {
    return colorScheme;
  }

  return 'light';
}
