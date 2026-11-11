import { useEffect, useSyncExternalStore } from 'react';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

export const periods = ['1W', '1M', 'YTD', '1Y', 'MAX'] as const;
export type HistoryPeriod = (typeof periods)[number];

const key = 'trackmywealth.history-period';
const defaultPeriod: HistoryPeriod = '1M';
const listeners = new Set<() => void>();
let period: HistoryPeriod = defaultPeriod;
let restoration: Promise<void> | undefined;
let writes = Promise.resolve();
let chosen = false;

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function update(next: HistoryPeriod) {
  period = next;
  listeners.forEach((listener) => listener());
}

function restorePeriod() {
  restoration ??= (async () => {
    try {
      const saved =
        Platform.OS === 'web'
          ? globalThis.localStorage?.getItem(key)
          : await SecureStore.getItemAsync(key);
      const known = periods.find((option) => option === saved);
      if (!chosen && known) update(known);
    } catch {
      // Keep the in-memory period when device storage is unavailable.
    }
  })();
}

export function selectHistoryPeriod(next: HistoryPeriod) {
  chosen = true;
  update(next);
  // Serialize native writes so a rapid series of taps persists the latest selection.
  writes = writes
    .then(async () => {
      if (Platform.OS === 'web') globalThis.localStorage?.setItem(key, next);
      else await SecureStore.setItemAsync(key, next);
    })
    .catch(() => {
      // The selection remains usable for this session.
    });
}

export function useHistoryPeriod() {
  const current = useSyncExternalStore(
    subscribe,
    () => period,
    () => defaultPeriod,
  );
  useEffect(restorePeriod, []);
  return current;
}
