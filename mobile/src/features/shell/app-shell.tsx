import { useEffect, useState } from 'react';
import { Slot, usePathname, useRouter, type Href } from 'expo-router';
import { AppState, Platform, Pressable, StyleSheet, View, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';
import { restoreAmountVisibility } from '@/features/privacy/amount-visibility';
import { OfflineBanner } from './offline-banner';
import { DESKTOP_NAVIGATION, isCurrentRoute, NAVIGATION } from './navigation';

export function AppShell() {
  const { width } = useWindowDimensions();
  const desktop = Platform.OS === 'web' && width >= 1024;
  const router = useRouter();
  const pathname = usePathname();
  const intl = useIntl();
  const theme = useTheme();
  const [backgrounded, setBackgrounded] = useState(false);

  useEffect(() => {
    void restoreAmountVisibility();
    if (Platform.OS === 'web') return;
    const sub = AppState.addEventListener('change', (state) => setBackgrounded(state !== 'active'));
    return () => sub.remove();
  }, []);

  const navigation = desktop ? DESKTOP_NAVIGATION : NAVIGATION;
  const menu = (
    <View accessibilityRole="tablist" style={desktop ? styles.sidebarMenu : styles.bottomMenu}>
      {navigation.map((item) => {
        const selected = isCurrentRoute(pathname, item.href);
        return (
          <Pressable
            key={item.href}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={intl.formatMessage({ id: item.labelKey })}
            onPress={() => router.navigate(item.href as Href)}
            style={({ pressed }) => [
              styles.navigationItem,
              desktop && styles.desktopItem,
              selected && { backgroundColor: theme.backgroundSelected },
              pressed && styles.pressed,
            ]}
          >
            <ThemedText style={styles.navIcon} accessibilityElementsHidden>{item.icon}</ThemedText>
            <ThemedText type="smallBold">
              {intl.formatMessage({ id: item.labelKey })}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );

  return (
    <SafeAreaView style={[styles.root, { backgroundColor: theme.background }]} edges={['top', 'bottom']}>
      <View style={styles.layout}>
        {desktop && (
          <View style={[styles.sidebar, { borderRightColor: theme.backgroundSelected }]}>
            <ThemedText type="smallBold" style={styles.brand}>TrackMyWealth</ThemedText>
            {menu}
          </View>
        )}
        <View style={styles.main}>
          <OfflineBanner />
          <View style={styles.content}><Slot /></View>
          {!desktop && menu}
        </View>
      </View>
      {backgrounded && Platform.OS !== 'web' && (
        <View
          style={[StyleSheet.absoluteFillObject, styles.privacyCover, { backgroundColor: theme.background }]}
          accessibilityLabel={intl.formatMessage({ id: 'privacy.protected' })}
          importantForAccessibility="yes"
        >
          <ThemedText type="subtitle">TrackMyWealth</ThemedText>
          <ThemedText>{intl.formatMessage({ id: 'privacy.protected' })}</ThemedText>
        </View>
      )}
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  root: { flex: 1 },
  layout: { flex: 1, flexDirection: 'row' },
  sidebar: { width: 248, paddingHorizontal: 16, paddingVertical: 24, borderRightWidth: 1 },
  brand: { fontSize: 20, marginBottom: 32 },
  sidebarMenu: { gap: 8 },
  bottomMenu: { flexDirection: 'row', justifyContent: 'space-around', paddingVertical: 6,
    borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: '#7B8793' },
  navigationItem: { minHeight: 48, minWidth: 96, paddingHorizontal: 12, paddingVertical: 8,
    borderRadius: 10, alignItems: 'center', justifyContent: 'center', gap: 2 },
  desktopItem: { flexDirection: 'row', justifyContent: 'flex-start', gap: 12 },
  navIcon: { fontSize: 19 },
  main: { flex: 1, minWidth: 0 },
  content: { flex: 1, minHeight: 0 },
  pressed: { opacity: 0.7 },
  privacyCover: { zIndex: 1000, justifyContent: 'center', alignItems: 'center', gap: 16 },
});
