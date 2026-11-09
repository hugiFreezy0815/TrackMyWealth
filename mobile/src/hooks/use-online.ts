import { onlineManager } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

/** TanStack Query's online state (#486: NetInfo on native, browser events on web), as a hook. */
export function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}
