import { useSyncExternalStore } from 'react';
import { onlineManager } from '@tanstack/react-query';

/** Whether the device is online, as TanStack Query's onlineManager (NetInfo) reports it. */
export function useOnline(): boolean {
  return useSyncExternalStore(
    (notify) => onlineManager.subscribe(notify),
    () => onlineManager.isOnline(),
    () => true,
  );
}
