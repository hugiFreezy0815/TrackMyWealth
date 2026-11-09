import { useRouter } from 'expo-router';
import { useCallback } from 'react';

import { signOut } from '@/features/auth/session';

/**
 * IA "Sign out": leave the current (possibly account-specific) route before the session ends, so
 * the next person signing in on this device starts on Overview instead of the previous member's
 * screen, and the browser's current history entry no longer names it. A session that merely
 * expires keeps its route on purpose: the same member returns to it after signing in again.
 */
export function useSignOut(): () => Promise<void> {
  const router = useRouter();
  return useCallback(async () => {
    router.replace('/overview');
    await signOut();
  }, [router]);
}
