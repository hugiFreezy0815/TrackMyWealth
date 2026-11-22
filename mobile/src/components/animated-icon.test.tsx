import { act, fireEvent, render, screen } from '@testing-library/react-native';
import * as SplashScreen from 'expo-splash-screen';
import { Animated, Platform } from 'react-native';

import { AnimatedSplashOverlay } from './animated-icon';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-splash-screen', () => ({ hide: jest.fn() }));

const start = jest.fn<void, Parameters<ReturnType<typeof Animated.timing>['start']>>();

beforeEach(() => {
  jest.useFakeTimers();
  jest.clearAllMocks();
  jest.spyOn(Animated, 'timing').mockReturnValue({
    start,
    stop: jest.fn(),
    reset: jest.fn(),
  });
});

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

it('waits for the logo and keeps the original image mounted through the Android fade', async () => {
  jest.replaceProperty(Platform, 'OS', 'android');
  await render(<AnimatedSplashOverlay />);
  const logo = screen.getByTestId('splash-logo');

  expect(SplashScreen.hide).not.toHaveBeenCalled();
  await fireEvent(logo, 'display');
  await fireEvent(logo, 'display');

  expect(SplashScreen.hide).toHaveBeenCalledTimes(1);
  expect(Animated.timing).toHaveBeenCalledTimes(1);
  expect(Animated.timing).toHaveBeenCalledWith(
    expect.any(Animated.Value),
    expect.objectContaining({ delay: 400, toValue: 0, useNativeDriver: true }),
  );
  expect(logo).toBeOnTheScreen();

  await act(() => start.mock.calls[0][0]?.({ finished: true }));
  expect(logo).not.toBeOnTheScreen();
});

it('releases the native splash if the bundled image reports an error', async () => {
  await render(<AnimatedSplashOverlay />);
  await fireEvent(screen.getByTestId('splash-logo'), 'error');
  expect(SplashScreen.hide).toHaveBeenCalledTimes(1);
});

it('removes the overlay even if the fade is interrupted', async () => {
  await render(<AnimatedSplashOverlay />);
  const logo = screen.getByTestId('splash-logo');
  await fireEvent(logo, 'display');

  await act(() => start.mock.calls[0][0]?.({ finished: false }));
  expect(logo).not.toBeOnTheScreen();
});

it('never takes touches', async () => {
  await render(<AnimatedSplashOverlay />);
  expect(screen.getByTestId('splash-overlay')).toHaveStyle({ pointerEvents: 'none' });
});

it('releases the native splash after 2 s but keeps the overlay until the logo displays', async () => {
  jest.replaceProperty(Platform, 'OS', 'android');
  await render(<AnimatedSplashOverlay />);
  await act(() => {
    jest.advanceTimersByTime(1999);
  });
  expect(SplashScreen.hide).not.toHaveBeenCalled();
  await act(() => {
    jest.advanceTimersByTime(1);
  });
  expect(SplashScreen.hide).toHaveBeenCalledTimes(1);
  expect(Animated.timing).not.toHaveBeenCalled();

  await act(() => {
    jest.advanceTimersByTime(150);
  });
  await fireEvent(screen.getByTestId('splash-logo'), 'display');
  expect(SplashScreen.hide).toHaveBeenCalledTimes(1);
  // Only the rest of the 400 ms Android splash exit is left to wait for.
  expect(Animated.timing).toHaveBeenCalledWith(
    expect.any(Animated.Value),
    expect.objectContaining({ delay: 250, toValue: 0 }),
  );
});

it('fades the overlay after 4 s if the logo never reports readiness', async () => {
  await render(<AnimatedSplashOverlay />);
  await act(() => {
    jest.advanceTimersByTime(3999);
  });
  expect(SplashScreen.hide).toHaveBeenCalledTimes(1);
  expect(Animated.timing).not.toHaveBeenCalled();
  await act(() => {
    jest.advanceTimersByTime(1);
  });
  expect(Animated.timing).toHaveBeenCalledTimes(1);
});
