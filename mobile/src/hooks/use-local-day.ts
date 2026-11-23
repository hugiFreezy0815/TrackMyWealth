import { useEffect, useState } from 'react';

import { localDay } from '@/format/date';

/**
 * Today's device-local day (YYYY-MM-DD), updated at local midnight, so "Today" / "Yesterday"
 * labels move on in a screen that stays open overnight.
 */
export function useLocalDay(): string {
  const [day, setDay] = useState(() => localDay(new Date()));
  useEffect(() => {
    const now = new Date();
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    const timer = setTimeout(
      () => setDay(localDay(new Date())),
      midnight.getTime() - now.getTime(),
    );
    return () => clearTimeout(timer);
  }, [day]);
  return day;
}
