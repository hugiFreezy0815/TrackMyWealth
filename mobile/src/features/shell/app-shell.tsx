import { useEffect } from 'react';
import { Slot, usePathname, useRouter } from 'expo-router';
import { Platform, Pressable, StyleSheet, View, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';
import { OverviewSetupGate } from '@/features/overview-name/setup-gate';
import { type Identity, isAdministrator } from '@/features/auth/session';
import { restoreAmountVisibility } from '@/features/privacy/amount-visibility';
import { useAppSwitcherProtection } from '@/features/privacy/screen-protection';

import { OfflineBanner } from './offline-banner';
import { isCurrentRoute, layoutFor, navigationFor } from './navigation';

/** W01: the 248 px desktop sidebar. */
export const SIDEBAR_WIDTH = 248;

/** The signed-in frame: navigation per layout, global banners and screen protection. */
export function AppShell({ identity }: { identity: Identity | null }) {
  const { width } = useWindowDimensions();
  const layout = layoutFor(width);
  const desktop = layout === 'desktop';
  const router = useRouter();
  const pathname = usePathname();
  const intl = useIntl();
  const theme = useTheme();

  useAppSwitcherProtection();
  useEffect(() => {
    void restoreAmountVisibility();
  }, []);

  // Web gets a navigation landmark of links (aria-current marks the page); native gets the
  // platform's tab semantics.
  const web = Platform.OS === 'web';
  const menu = (
    <View
      role={web ? 'navigation' : 'tablist'}
      accessibilityLabel={intl.formatMessage({ id: 'nav.main' })}
      style={desktop ? styles.sidebarMenu : [styles.bottomMenu, { borderTopColor: theme.border }]}
    >
      {navigationFor(layout, isAdministrator(identity)).map((item) => {
        const selected = isCurrentRoute(pathname, item.href);
        const label = intl.formatMessage({ id: item.labelKey });
        return (
          <Pressable
            key={item.href}
            role={web ? 'link' : 'tab'}
            accessibilityLabel={label}
            accessibilityState={{ selected }}
            {...(web && selected ? { 'aria-current': 'page' } : {})}
            onPress={() => router.navigate(item.href)}
            style={({ pressed }) => [
              styles.navigationItem,
              desktop && styles.desktopItem,
              selected && { backgroundColor: theme.backgroundSelected },
              pressed && styles.pressed,
            ]}
          >
            <ThemedText
              style={styles.navIcon}
              accessibilityElementsHidden
              importantForAccessibility="no-hide-descendants"
            >
              {item.icon}
            </ThemedText>
            <ThemedText type="smallBold">{label}</ThemedText>
          </Pressable>
        );
      })}
    </View>
  );

  return (
    <SafeAreaView
      style={[styles.root, { backgroundColor: theme.background }]}
      edges={['top', 'bottom']}
    >
      <View style={styles.layout}>
        {desktop && (
          <View
            testID="shell-sidebar"
            style={[styles.sidebar, { borderRightColor: theme.backgroundSelected }]}
          >
            <ThemedText type="smallBold" style={styles.brand}>
              {intl.formatMessage({ id: 'app.name' })}
            </ThemedText>
            {menu}
          </View>
        )}
        <View style={styles.main}>
          <OfflineBanner />
          <View style={styles.content}>
            {identity && !isAdministrator(identity) ? (
              <OverviewSetupGate userId={identity.userId}>
                <Slot />
              </OverviewSetupGate>
            ) : (
              <Slot />
            )}
          </View>
          {!desktop && menu}
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  layout: { flex: 1, flexDirection: 'row' },
  sidebar: {
    width: SIDEBAR_WIDTH,
    paddingHorizontal: 16,
    paddingVertical: 24,
    borderRightWidth: 1,
  },
  brand: { fontSize: 20, marginBottom: 32 },
  sidebarMenu: { gap: 8 },
  bottomMenu: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 6,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  navigationItem: {
    minHeight: 48,
    minWidth: 96,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
  },
  desktopItem: { flexDirection: 'row', justifyContent: 'flex-start', gap: 12 },
  navIcon: { fontSize: 19 },
  main: { flex: 1, minWidth: 0 },
  content: { flex: 1, minHeight: 0 },
  pressed: { opacity: 0.7 },
});
