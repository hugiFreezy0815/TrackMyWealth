import { useSyncExternalStore } from 'react';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

/**
 * FR-CLI-005 "Hide amounts": a device preference, not an account setting, so it is never sent to
 * the server. Native keeps it in the secure store next to the session; web in localStorage (it
 * is not a secret, only a choice).
 */
export const HIDE_AMOUNTS_KEY = 'trackmywealth.hide-amounts';

const listeners = new Set<() => void>();
// Hidden until the saved preference is read: no amount flashes on start-up.
let hidden = true;
let restore: Promise<void> | null = null;
// A choice made while the saved value is still loading wins over that (older) value.
let chosenThisSession = false;

function emit() {
  listeners.forEach((listener) => listener());
}

export function subscribeAmounts(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function amountsHidden(): boolean {
  return hidden;
}

export function useAmountsHidden(): boolean {
  return useSyncExternalStore(subscribeAmounts, amountsHidden, () => true);
}

async function readSaved(): Promise<string | null> {
  if (Platform.OS === 'web') {
    // No storage at all (static rendering): treat as unreadable, i.e. stay private.
    const storage = globalThis.localStorage;
    return storage ? storage.getItem(HIDE_AMOUNTS_KEY) : 'true';
  }
  return SecureStore.getItemAsync(HIDE_AMOUNTS_KEY);
}

async function writeSaved(value: boolean): Promise<void> {
  if (Platform.OS === 'web') {
    globalThis.localStorage?.setItem(HIDE_AMOUNTS_KEY, String(value));
    return;
  }
  await SecureStore.setItemAsync(HIDE_AMOUNTS_KEY, String(value));
}

/** Reads the saved preference once per app start; later calls share the first read. */
export function restoreAmountVisibility(): Promise<void> {
  restore ??= (async () => {
    let saved: string | null;
    try {
      saved = await readSaved();
    } catch {
      // Storage can be blocked (private browsing, locked keychain): stay private.
      saved = 'true';
    }
    if (chosenThisSession) return;
    // Nothing saved yet (first start) shows amounts; a saved value other than "false" hides them.
    hidden = saved !== null && saved !== 'false';
    emit();
  })();
  return restore;
}

export async function setAmountsHidden(value: boolean): Promise<void> {
  chosenThisSession = true;
  hidden = value;
  emit();
  try {
    await writeSaved(value);
  } catch {
    // Keep the in-memory preference for this session; storage may be unavailable.
  }
}
