import { Image } from 'expo-image';
import * as SplashScreen from 'expo-splash-screen';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Animated, Easing, Platform, StyleSheet, useColorScheme } from 'react-native';

import { DesignTokens } from '@/constants/theme';

// Expo draws the Android 12+ splash logo at imageWidth dp on the 288 dp system icon canvas,
// well inside its 192 dp mask, and iOS uses imageWidth directly. Keep this equal to the
// imageWidth in app.json so the overlay logo lines up with the native one.
const IMAGE_SIZE = 76;
// Expo SDK 58 fades the Android system splash for 400 ms after hide().
const ANDROID_SPLASH_EXIT_MS = 400;
const FADE_MS = 200;
// Release the native splash if the logo has not displayed by then, so startup never stalls.
const SPLASH_TIMEOUT_MS = 2000;
// Fade the overlay even if the logo never displays, so it can never cover the app for good.
const OVERLAY_TIMEOUT_MS = 4000;

export function AnimatedSplashOverlay() {
  const scheme = useColorScheme();
  const backgroundColor = DesignTokens[scheme === 'dark' ? 'dark' : 'light'].bgCanvas;
  const [visible, setVisible] = useState(true);
  const [opacity] = useState(() => new Animated.Value(1));
  const splashHiddenAt = useRef<number | null>(null);
  const didStartFade = useRef(false);

  const hideNativeSplash = useCallback(() => {
    if (splashHiddenAt.current !== null) return;
    splashHiddenAt.current = Date.now();
    SplashScreen.hide();
  }, []);

  const fadeOut = useCallback(() => {
    if (didStartFade.current) return;
    didStartFade.current = true;

    hideNativeSplash();
    // Wait for whatever is left of the Android system splash exit before fading.
    const nativeExitLeft =
      Platform.OS === 'android'
        ? Math.max(0, ANDROID_SPLASH_EXIT_MS - (Date.now() - (splashHiddenAt.current ?? 0)))
        : 0;
    Animated.timing(opacity, {
      toValue: 0,
      delay: nativeExitLeft,
      duration: FADE_MS,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    }).start(() => {
      // Unmount even if the fade was interrupted, so no invisible overlay stays on top.
      setVisible(false);
    });
  }, [hideNativeSplash, opacity]);

  useEffect(() => {
    const splashTimeout = setTimeout(hideNativeSplash, SPLASH_TIMEOUT_MS);
    const overlayTimeout = setTimeout(fadeOut, OVERLAY_TIMEOUT_MS);
    return () => {
      clearTimeout(splashTimeout);
      clearTimeout(overlayTimeout);
      opacity.stopAnimation();
    };
  }, [fadeOut, hideNativeSplash, opacity]);

  if (!visible) return null;

  return (
    <Animated.View
      style={[styles.splashOverlay, { backgroundColor, opacity }]}
      testID="splash-overlay"
    >
      <Image
        accessible={false}
        contentFit="contain"
        style={styles.image}
        source={require('@/assets/images/splash-icon.png')}
        testID="splash-logo"
        onDisplay={fadeOut}
        onError={fadeOut}
      />
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  image: {
    width: IMAGE_SIZE,
    height: IMAGE_SIZE,
  },
  splashOverlay: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    // The native splash blocks input until it hides; the overlay never should.
    pointerEvents: 'none',
    zIndex: 1000,
  },
});
