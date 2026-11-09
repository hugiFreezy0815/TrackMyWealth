import { renderHook } from '@testing-library/react-native';
import { Platform } from 'react-native';
import * as ScreenCapture from 'expo-screen-capture';

import {
  APP_SWITCHER_BLUR,
  SCREEN_PROTECTION_KEY,
  useAppSwitcherProtection,
} from './screen-protection';

jest.mock('expo-screen-capture', () => ({
  enableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  disableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  preventScreenCaptureAsync: jest.fn(async () => {}),
  allowScreenCaptureAsync: jest.fn(async () => {}),
}));

const capture = jest.mocked(ScreenCapture);
const ORIGINAL_OS = Platform.OS;

afterEach(() => {
  Platform.OS = ORIGINAL_OS;
  jest.clearAllMocks();
});

describe('app-switcher protection (FR-CLI-005)', () => {
  it('blurs the iOS snapshot natively while signed in and lifts it on sign-out', async () => {
    Platform.OS = 'ios';
    const view = await renderHook(() => useAppSwitcherProtection());

    expect(capture.enableAppSwitcherProtectionAsync).toHaveBeenCalledWith(APP_SWITCHER_BLUR);
    expect(capture.preventScreenCaptureAsync).not.toHaveBeenCalled();

    await view.unmount();
    expect(capture.disableAppSwitcherProtectionAsync).toHaveBeenCalled();
  });

  it('marks the Android window secure, so recents never shows a snapshot', async () => {
    Platform.OS = 'android';
    const view = await renderHook(() => useAppSwitcherProtection());

    expect(capture.preventScreenCaptureAsync).toHaveBeenCalledWith(SCREEN_PROTECTION_KEY);

    await view.unmount();
    expect(capture.allowScreenCaptureAsync).toHaveBeenCalledWith(SCREEN_PROTECTION_KEY);
  });

  it('does not call native capture APIs on web', async () => {
    Platform.OS = 'web';
    await renderHook(() => useAppSwitcherProtection());

    expect(capture.enableAppSwitcherProtectionAsync).not.toHaveBeenCalled();
    expect(capture.preventScreenCaptureAsync).not.toHaveBeenCalled();
  });

  it('keeps the app usable when the device refuses protection', async () => {
    Platform.OS = 'android';
    capture.preventScreenCaptureAsync.mockRejectedValueOnce(new Error('unavailable'));

    await expect(renderHook(() => useAppSwitcherProtection())).resolves.toBeDefined();
  });
});
