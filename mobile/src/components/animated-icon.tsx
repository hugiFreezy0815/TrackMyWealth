import { Image } from 'expo-image';
import * as SplashScreen from 'expo-splash-screen';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Animated, Easing, Platform, StyleSheet, useColorScheme } from 'react-native';

import { DesignTokens } from '@/constants/theme';

// Match the image width configured in app.json.
const IMAGE_SIZE = 76;
// Expo SDK 58 fades the Android system splash for 400 ms after hide().
const ANDROID_SPLASH_EXIT_MS = 400;
const FADE_MS = 200;
const IMAGE_TIMEOUT_MS = 2000;

export function AnimatedSplashOverlay() {
  const scheme = useColorScheme();
  const backgroundColor = DesignTokens[scheme === 'dark' ? 'dark' : 'light'].bgCanvas;
  const [visible, setVisible] = useState(true);
  const [opacity] = useState(() => new Animated.Value(1));
  const didHideSplash = useRef(false);

  const hideSplash = useCallback(() => {
    if (didHideSplash.current) return;
    didHideSplash.current = true;

    SplashScreen.hide();
    Animated.timing(opacity, {
      toValue: 0,
      delay: Platform.OS === 'android' ? ANDROID_SPLASH_EXIT_MS : 0,
      duration: FADE_MS,
      easing: Easing.out(Easing.quad),
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished) setVisible(false);
    });
  }, [opacity]);

  useEffect(() => {
    // Ensure startup can finish even if the bundled image never reports a display or error.
    const timeout = setTimeout(hideSplash, IMAGE_TIMEOUT_MS);
    return () => {
      clearTimeout(timeout);
      opacity.stopAnimation();
    };
  }, [hideSplash, opacity]);

  if (!visible) return null;

  return (
    <Animated.View style={[styles.splashOverlay, { backgroundColor, opacity }]}>
      <Image
        accessible={false}
        contentFit="contain"
        style={styles.image}
        source={require('@/assets/images/splash-icon.png')}
        testID="splash-logo"
        onDisplay={hideSplash}
        onError={hideSplash}
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
    zIndex: 1000,
  },
});
