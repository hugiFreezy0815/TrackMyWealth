import { Platform } from 'react-native';

// A restored document can still hold an earlier session in memory. Share only a
// sign-out marker across documents, so the restored page can discard that state.
const SIGN_OUT_MARKER = 'trackmywealth.sign-out';

function readSignOutMarker(): string | null | undefined {
  try {
    return window.localStorage.getItem(SIGN_OUT_MARKER);
  } catch {
    return undefined;
  }
}

/** Loads a fresh document after sign-out, discarding the current page's in-memory state. */
export function replaceDocument(path: string): void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(SIGN_OUT_MARKER, window.crypto.randomUUID());
  } catch {
    // A browser that denies storage also denies the guard its marker.
  }
  window.location.replace(path);
}

/** Reloads a restored page if it predates a sign-out or cannot check the sign-out marker. */
export function installBackForwardGuard(isSignedIn: () => boolean): () => void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return () => undefined;
  const initialMarker = readSignOutMarker();
  const onPageShow = (event: PageTransitionEvent) => {
    if (
      event.persisted &&
      (!isSignedIn() || initialMarker === undefined || initialMarker !== readSignOutMarker())
    ) {
      window.location.reload();
    }
  };
  window.addEventListener('pageshow', onPageShow);
  return () => window.removeEventListener('pageshow', onPageShow);
}
