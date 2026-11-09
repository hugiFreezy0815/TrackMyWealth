import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/**
 * The optional introductory step is a device choice, not a server preference.
 * Scope it to both member and overview id so another login on the same device
 * never silently inherits someone's completed onboarding.
 */
export function setupStepKey(userId: string, overviewId: string): string {
  return 'tmw.overview-name-setup.' + userId + '.' + overviewId;
}

export async function hasCompletedOverviewStep(
  userId: string,
  overviewId: string,
): Promise<boolean> {
  const key = setupStepKey(userId, overviewId);
  try {
    if (Platform.OS === 'web') return globalThis.localStorage?.getItem(key) === 'done';
    return (await SecureStore.getItemAsync(key)) === 'done';
  } catch {
    // Missing or inaccessible storage never falsely marks setup as finished.
    return false;
  }
}

export async function completeOverviewStep(userId: string, overviewId: string): Promise<void> {
  const key = setupStepKey(userId, overviewId);
  try {
    if (Platform.OS === 'web') globalThis.localStorage?.setItem(key, 'done');
    else await SecureStore.setItemAsync(key, 'done');
  } catch {
    // The completed choice remains in memory via the gate's state even when
    // a locked keychain or private browsing prevents persistence.
  }
}
