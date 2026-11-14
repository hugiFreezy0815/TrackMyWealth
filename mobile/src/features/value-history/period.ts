import { useEffect, useSyncExternalStore } from 'react';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

import type { components } from '@/api/schema';

/** The contract's period enum (1W, 1M, YTD, 1Y, MAX), in display order. */
export type HistoryPeriod = components['schemas']['ValueHistoryResponse']['period'];
export const periods = ['1W', '1M', 'YTD', '1Y', 'MAX'] as const satisfies readonly HistoryPeriod[];

export const HISTORY_PERIOD_KEY = 'trackmywealth.history-period';
const defaultPeriod: HistoryPeriod = '1M';
const listeners = new Set<() => void>();
let period: HistoryPeriod = defaultPeriod;
let restoration: Promise<void> | undefined;
let writes = Promise.resolve();
let chosen = false;

export function isHistoryPeriod(value: unknown): value is HistoryPeriod {
  return periods.some((option) => option === value);
}

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

function readSaved() {
  return Platform.OS === 'web'
    ? Promise.resolve(globalThis.localStorage?.getItem(HISTORY_PERIOD_KEY))
    : SecureStore.getItemAsync(HISTORY_PERIOD_KEY);
}

function restorePeriod() {
  restoration ??= (async () => {
    try {
      const saved = await readSaved();
      // A choice made while the read was pending wins over the saved one.
      if (!chosen && isHistoryPeriod(saved)) update(saved);
    } catch {
      // Storage unavailable (e.g. the keychain before first unlock): keep the in-memory period and
      // let the next screen that mounts try again.
      restoration = undefined;
    }
  })();
}

export function selectHistoryPeriod(next: HistoryPeriod) {
  chosen = true;
  update(next);
  // Serialize native writes so a rapid series of taps persists the latest selection.
  writes = writes
    .then(async () => {
      if (Platform.OS === 'web') globalThis.localStorage?.setItem(HISTORY_PERIOD_KEY, next);
      else await SecureStore.setItemAsync(HISTORY_PERIOD_KEY, next);
    })
    .catch(() => {
      // The selection remains usable for this session.
    });
}

/** Resolves once every selection made so far has been written (or failed). */
export function historyPeriodWrites(): Promise<void> {
  return writes;
}

/** Test support: forget the in-memory state so the next mount restores from storage again. */
export function resetHistoryPeriodForTests() {
  period = defaultPeriod;
  restoration = undefined;
  writes = Promise.resolve();
  chosen = false;
  listeners.forEach((listener) => listener());
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
