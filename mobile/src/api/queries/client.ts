import { AppState, Platform } from 'react-native';
import NetInfo from '@react-native-community/netinfo';
import { focusManager, onlineManager, QueryClient } from '@tanstack/react-query';

import { retryDelay, shouldRetry } from './retry';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: shouldRetry, retryDelay, staleTime: 30_000 },
    mutations: { retry: false },
  },
});

let started = false;
export function configureQueryEnvironment(): () => void {
  if (started) return () => undefined;
  started = true;
  let unsubscribe: () => void;
  if (Platform.OS === 'web') {
    const onOnline = () => onlineManager.setOnline(true);
    const onOffline = () => onlineManager.setOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    onlineManager.setOnline(navigator.onLine);
    unsubscribe = () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  } else {
    unsubscribe = NetInfo.addEventListener((state) =>
      onlineManager.setOnline(Boolean(state.isConnected) && state.isInternetReachable !== false),
    );
  }
  const appSubscription = AppState.addEventListener('change', (state) => {
    focusManager.setFocused(state === 'active');
  });
  return () => {
    unsubscribe();
    appSubscription.remove();
    started = false;
  };
}
