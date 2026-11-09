import { Platform } from 'react-native';

/**
 * Web-only document controls for sign-out (IA "Sign out": clear cached data, invalidate browser
 * state where feasible). A page cannot delete browser history; what it can do is make sure no
 * earlier document survives with data in memory.
 */

/** Loads a fresh document, discarding every in-memory state of the current one. */
export function replaceDocument(path: string): void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return;
  window.location.replace(path);
}

/**
 * Back/forward can restore an earlier document from the browser's back-forward cache with its old
 * JS state. A restored document that is signed out is reloaded, so no earlier member's state
 * lingers in it. A signed-in one is kept: web holds tokens in memory only, so reloading it would
 * sign the member out just for pressing Back. Returns the uninstall function.
 */
export function installBackForwardGuard(isSignedIn: () => boolean): () => void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return () => undefined;
  const onPageShow = (event: PageTransitionEvent) => {
    if (event.persisted && !isSignedIn()) window.location.reload();
  };
  window.addEventListener('pageshow', onPageShow);
  return () => window.removeEventListener('pageshow', onPageShow);
}
