import { Platform } from 'react-native';

/**
 * Web-only document controls for sign-out (#487, #535, #536). A page cannot delete browser
 * history; it can make sure no earlier document resumes a session after a sign-out.
 *
 * Every sign-out writes a new random sign-out marker. Each document remembers the marker it saw
 * at start-up. A document the browser restores from its back-forward cache keeps its in-memory
 * session only when it can prove that no sign-out happened since: both stores still hold its
 * marker. Otherwise it reloads, which discards the session (web tokens live in memory only).
 *
 * The marker is kept in localStorage (all tabs) and sessionStorage (this tab, where back-forward
 * restores happen). A failed write removes the old value instead, because removing needs no
 * quota, so a restored document sees a changed marker and reloads.
 */
const SIGN_OUT_MARKER = 'trackmywealth.sign-out';

type MarkerStore = 'localStorage' | 'sessionStorage';
const STORES: readonly MarkerStore[] = ['localStorage', 'sessionStorage'];

function newMarker(): string {
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** The stored marker, or undefined when the store cannot be read. */
function readMarker(store: MarkerStore): string | null | undefined {
  try {
    return window[store].getItem(SIGN_OUT_MARKER);
  } catch {
    return undefined;
  }
}

/** Replaces the marker; when that fails, removes it so no reader keeps seeing the old value. */
function writeMarker(store: MarkerStore, value: string): void {
  try {
    window[store].setItem(SIGN_OUT_MARKER, value);
  } catch {
    try {
      window[store].removeItem(SIGN_OUT_MARKER);
    } catch {
      // Storage refused entirely; readers then fail to read and reload.
    }
  }
}

/** The markers this document starts with; a store without one gets a fresh value. */
function startMarkers(): (string | undefined)[] {
  return STORES.map((store) => {
    const current = readMarker(store);
    if (current !== null) return current;
    writeMarker(store, newMarker());
    // Read back: a write that did not stick leaves null, which no later check can trust.
    return readMarker(store) ?? undefined;
  });
}

/** Records a sign-out, then loads a fresh document, discarding all in-memory state. */
export function replaceDocument(path: string): void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return;
  const marker = newMarker();
  STORES.forEach((store) => writeMarker(store, marker));
  window.location.replace(path);
}

/**
 * Reloads a document restored from the back-forward cache when it is signed out, or when it
 * cannot prove that no sign-out happened since it started. A signed-in member who left the site
 * and pressed Back keeps the session (#535). Returns the uninstall function.
 */
export function installBackForwardGuard(isSignedIn: () => boolean): () => void {
  if (Platform.OS !== 'web' || typeof window === 'undefined') return () => undefined;
  const startedWith = startMarkers();
  const onPageShow = (event: PageTransitionEvent) => {
    if (!event.persisted) return;
    const unchanged = STORES.every((store, index) => {
      const marker = startedWith[index];
      return marker !== undefined && readMarker(store) === marker;
    });
    if (!isSignedIn() || !unchanged) window.location.reload();
  };
  window.addEventListener('pageshow', onPageShow);
  return () => window.removeEventListener('pageshow', onPageShow);
}
