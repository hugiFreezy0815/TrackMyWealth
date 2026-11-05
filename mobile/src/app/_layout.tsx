import { useEffect, useState } from 'react';
import { DarkTheme, DefaultTheme, Slot, ThemeProvider, usePathname } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { Platform, View, useColorScheme } from 'react-native';

import { loadServerUrl } from '@/api/server-url';
import { AnimatedSplashOverlay } from '@/components/animated-icon';
import AppTabs from '@/components/app-tabs';
import { ThemedText } from '@/components/themed-text';
import { AuthScreen } from '@/features/auth/auth-screen';
import { restoreSession, sessionSnapshot, subscribeSession } from '@/features/auth/session';
import { SessionBar } from '@/features/auth/session-bar';

SplashScreen.preventAutoHideAsync();

export default function TabLayout() {
  const colorScheme = useColorScheme();
  const pathname = usePathname();
  const [session, setSession] = useState(sessionSnapshot);
  const [restoring, setRestoring] = useState(true);

  useEffect(() => {
    const unsubscribe = subscribeSession(() => setSession(sessionSnapshot()));
    // The stored session belongs to the configured server, so that address comes first.
    loadServerUrl()
      .then(restoreSession)
      .catch(() => undefined)
      .finally(() => setRestoring(false));
    return unsubscribe;
  }, []);

  // The synthetic, web-only benchmark must not require real credentials or hit any backend.
  // This bypass exists ONLY on the disposable experiment branch; do not merge it to main.
  if (Platform.OS === 'web' && pathname === '/spike-web-transactions') {
    return <Slot />;
  }

  return (
    <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
      <AnimatedSplashOverlay />
      {restoring ? (
        <View accessibilityLabel="Restoring session">
          <ThemedText>Restoring session…</ThemedText>
        </View>
      ) : session.signedIn ? (
        <>
          <SessionBar identity={session.identity} />
          <AppTabs identity={session.identity} />
        </>
      ) : (
        <AuthScreen mfaRequired={session.mfaRequired} />
      )}
    </ThemeProvider>
  );
}
