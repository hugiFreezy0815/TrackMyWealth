import { Platform } from 'react-native';

/** Loads a fresh document after sign-out, discarding the current page's in-memory state. */
export function replaceDocument(path: string): void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return;
  window.location.replace(path);
}

/** Reloads restored documents so a cached page cannot resume an earlier in-memory session. */
export function installBackForwardGuard(): () => void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return () => undefined;
  const onPageShow = (event: PageTransitionEvent) => {
    // An unchanged storage value cannot prove another document was able to record sign-out.
    if (event.persisted) window.location.reload();
  };
  window.addEventListener('pageshow', onPageShow);
  return () => window.removeEventListener('pageshow', onPageShow);
}
