import { useRouter } from 'expo-router';
import { useCallback } from 'react';

import { signOut } from '@/features/auth/session';
import { replaceDocument } from '@/features/auth/web-document';

/**
 * IA "Sign out": leave the current (possibly account-specific) route before the session ends, so
 * the next person signing in on this device starts on Overview instead of the previous member's
 * screen. On web the page is then reloaded, so nothing of the session survives in memory, not
 * only what the app knows to clear (browser history itself cannot be erased by a page). A session
 * that merely expires keeps its route on purpose: the same member returns to it after signing in.
 */
export function useSignOut(): () => Promise<void> {
  const router = useRouter();
  return useCallback(async () => {
    router.replace('/overview');
    await signOut();
    replaceDocument('/overview');
  }, [router]);
}
