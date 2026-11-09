import { useEffect, useState } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';

import { configureQueryEnvironment, queryClient } from '@/api/queries/client';
import { AppIntlProvider } from '@/i18n';
import { DarkTheme, DefaultTheme, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { View, useColorScheme } from 'react-native';

import { loadServerUrl } from '@/api/server-url';
import { AnimatedSplashOverlay } from '@/components/animated-icon';
import { AppShell } from '@/features/shell/app-shell';
import { ThemedText } from '@/components/themed-text';
import { AuthScreen } from '@/features/auth/auth-screen';
import { restoreSession, sessionSnapshot, subscribeSession } from '@/features/auth/session';


SplashScreen.preventAutoHideAsync();

export default function TabLayout() {
  const colorScheme = useColorScheme();
  const [session, setSession] = useState(sessionSnapshot);
  const [restoring, setRestoring] = useState(true);

  useEffect(() => {
    const stopQueryEnvironment = configureQueryEnvironment();
    const unsubscribe = subscribeSession(() => setSession(sessionSnapshot()));
    // The stored session belongs to the configured server, so that address comes first.
    loadServerUrl()
      .then(restoreSession)
      .catch(() => undefined)
      .finally(() => setRestoring(false));
    return () => {
      unsubscribe();
      stopQueryEnvironment();
    };
  }, []);

  useEffect(() => {
    // A cache must never expose the previous member's financial data after sign-out.
    if (!session.signedIn) queryClient.clear();
  }, [session.signedIn]);

  return (
    <QueryClientProvider client={queryClient}>
      <AppIntlProvider language={session.identity?.language}>
        <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
          <AnimatedSplashOverlay />
          {restoring ? (
            <View accessibilityLabel="Restoring session">
              <ThemedText>Restoring session…</ThemedText>
            </View>
          ) : session.signedIn ? (
            <AppShell />
          ) : (
            <AuthScreen mfaRequired={session.mfaRequired} />
          )}
        </ThemeProvider>
      </AppIntlProvider>
    </QueryClientProvider>
  );
}
