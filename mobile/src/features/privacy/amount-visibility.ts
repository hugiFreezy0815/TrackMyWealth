import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import { useSyncExternalStore } from 'react';

const KEY = 'trackmywealth.hide-amounts';
const listeners = new Set<() => void>();
let hidden = true;
let restored = false;

function emit() { listeners.forEach((listener) => listener()); }
export function subscribeAmounts(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
export function amountsHidden(): boolean { return hidden; }
export function useAmountsHidden(): boolean {
  return useSyncExternalStore(subscribeAmounts, amountsHidden, () => true);
}
/** Hide until the preference is loaded: no sensitive value flashes on startup. */
export async function restoreAmountVisibility(): Promise<void> {
  if (restored) return;
  let value: string | null = null;
  try {
    value = Platform.OS === 'web'
      ? (typeof window === 'undefined' ? 'true' : window.localStorage.getItem(KEY))
      : await SecureStore.getItemAsync(KEY);
  } catch {
    // Storage can be blocked. Default to private until the member changes the setting.
    value = 'true';
  }
  hidden = value === 'true';
  restored = true;
  emit();
}
export async function setAmountsHidden(value: boolean): Promise<void> {
  hidden = value;
  emit();
  try {
    if (Platform.OS === 'web') {
      if (typeof window !== 'undefined') window.localStorage.setItem(KEY, String(value));
    } else {
      await SecureStore.setItemAsync(KEY, String(value));
    }
  } catch {
    // Keep the in-memory preference for this session; storage may be unavailable.
  }
}
