import { useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';

/**
 * Whether the device is online, from TanStack Query's connectivity source (wired to NetInfo on
 * native and the browser's online events on web in `configureQueryEnvironment`). The one place
 * screens read it, so a banner and a disabled button can never disagree.
 */
export function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}
