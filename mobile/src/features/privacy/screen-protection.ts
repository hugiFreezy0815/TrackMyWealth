import { useEffect } from 'react';
import { Platform } from 'react-native';
import * as ScreenCapture from 'expo-screen-capture';

/** Shared with any later screen that also prevents capture, so allowing one never lifts the other. */
export const SCREEN_PROTECTION_KEY = 'trackmywealth.app-switcher';
/** Strong enough that no amount stays legible in the app-switcher snapshot (0–1). */
export const APP_SWITCHER_BLUR = 1;

/**
 * FR-CLI-005: while signed in, the system app switcher never shows financial data.
 *
 * Done natively, because a React overlay rendered on an AppState change arrives after the OS has
 * taken the snapshot (Android captures recents as the activity pauses):
 * - iOS blurs the window on willResignActive, before the snapshot is taken;
 * - Android sets FLAG_SECURE, so recents shows a blank card. The same flag also blocks screenshots
 *   and screen recording of the app; Android offers no recents-only protection below API 33.
 * Web has no app switcher the app can protect; "Hide amounts" is the protection there.
 */
export function useAppSwitcherProtection(): void {
  useEffect(() => {
    if (Platform.OS === 'ios') {
      void ScreenCapture.enableAppSwitcherProtectionAsync(APP_SWITCHER_BLUR).catch(ignore);
      return () => void ScreenCapture.disableAppSwitcherProtectionAsync().catch(ignore);
    }
    if (Platform.OS === 'android') {
      void ScreenCapture.preventScreenCaptureAsync(SCREEN_PROTECTION_KEY).catch(ignore);
      return () => void ScreenCapture.allowScreenCaptureAsync(SCREEN_PROTECTION_KEY).catch(ignore);
    }
    return undefined;
  }, []);
}

// A device without the native API cannot be protected further; amounts stay hideable regardless.
function ignore(): void {}
