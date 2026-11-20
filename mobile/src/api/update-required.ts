/**
 * US-29-16 (#576): whether the server has refused this app version (426 APP_UPDATE_REQUIRED). The
 * transport (client.ts) reports it, and the root layout shows the update screen instead of the app
 * while it is set. Every request would fail the same way, so one screen replaces every per-screen
 * error.
 */

let required = false;
const listeners = new Set<() => void>();

export function updateRequiredSnapshot(): boolean {
  return required;
}

export function subscribeUpdateRequired(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Called by the transport for every 426: the server no longer serves this app version. */
export function reportUpdateRequired(): void {
  if (required) return;
  required = true;
  listeners.forEach((listener) => listener());
}

/**
 * Hides the update screen to try again, for a server whose minimum was lowered meanwhile. The next
 * 426 shows it again.
 */
export function dismissUpdateRequired(): void {
  if (!required) return;
  required = false;
  listeners.forEach((listener) => listener());
}
