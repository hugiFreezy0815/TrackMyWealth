import NetInfo, { type NetInfoState } from '@react-native-community/netinfo';
import { onlineManager } from '@tanstack/react-query';

import { configureQueryEnvironment } from './client';

jest.mock('@react-native-community/netinfo', () => ({
  __esModule: true,
  default: { addEventListener: jest.fn() },
}));
const mockedAddEventListener = jest.mocked(NetInfo.addEventListener);

describe('query environment (iOS/Android)', () => {
  afterEach(() => {
    onlineManager.setOnline(true);
    // Only this mock: resetAllMocks would also strip jest-expo's AppState mock.
    mockedAddEventListener.mockReset();
  });

  it('pauses queries while NetInfo reports no internet and resumes when it is back', () => {
    const unsubscribeNetInfo = jest.fn();
    let listener: (state: NetInfoState) => void = () => undefined;
    mockedAddEventListener.mockImplementation((callback) => {
      listener = callback;
      return unsubscribeNetInfo;
    });

    const stop = configureQueryEnvironment();
    listener({ isConnected: true, isInternetReachable: false } as NetInfoState);
    expect(onlineManager.isOnline()).toBe(false);
    listener({ isConnected: false, isInternetReachable: null } as NetInfoState);
    expect(onlineManager.isOnline()).toBe(false);
    listener({ isConnected: true, isInternetReachable: null } as NetInfoState);
    expect(onlineManager.isOnline()).toBe(true);

    stop();
    expect(unsubscribeNetInfo).toHaveBeenCalledTimes(1);
  });

  it('subscribes once even when the root layout mounts twice', () => {
    mockedAddEventListener.mockReturnValue(jest.fn());
    const stopFirst = configureQueryEnvironment();
    const stopSecond = configureQueryEnvironment();
    expect(mockedAddEventListener).toHaveBeenCalledTimes(1);
    stopSecond();
    stopFirst();
  });
});
