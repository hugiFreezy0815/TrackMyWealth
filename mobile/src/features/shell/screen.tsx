import { useRouter } from 'expo-router';
import type { ReactElement, ReactNode } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  View,
  useWindowDimensions,
  type RefreshControlProps,
} from 'react-native';
import { useIntl } from 'react-intl';

import { ThemedText } from '@/components/themed-text';
import { useTheme } from '@/hooks/use-theme';
import { AmountVisibilityToggle } from '@/features/privacy/amount-visibility-toggle';

import { layoutFor } from './navigation';

/** W01/W06: the nominal width of the desktop detail panel. */
export const DETAIL_PANEL_WIDTH = 360;

type Props = {
  title: string;
  children: ReactNode;
  /** Contextual detail (W01 right panel): beside the content on desktop, below it otherwise. */
  aside?: ReactNode;
  /** The eye control on every screen (FR-CLI-005); Settings shows its own labelled switch. */
  privacyToggle?: boolean;
  /** Pull-to-refresh for the whole screen (a `RefreshControl`). */
  refreshControl?: ReactElement<RefreshControlProps>;
};

export function ShellScreen({
  title,
  children,
  aside,
  privacyToggle = true,
  refreshControl,
}: Props) {
  const theme = useTheme();
  const { width } = useWindowDimensions();
  const layout = layoutFor(width);
  const besideContent = aside != null && layout === 'desktop';
  return (
    <ScrollView
      style={{ backgroundColor: theme.background }}
      refreshControl={refreshControl}
      contentContainerStyle={[styles.scroll, layout === 'phone' && styles.scrollPhone]}
    >
      <View style={[styles.frame, besideContent && styles.frameWithPanel]}>
        <View style={styles.content}>
          <View style={styles.header}>
            <ThemedText type="subtitle" role="heading" style={styles.heading}>
              {title}
            </ThemedText>
            {privacyToggle && <AmountVisibilityToggle />}
          </View>
          {children}
        </View>
        {aside != null && (
          <View
            role="complementary"
            testID="shell-detail-panel"
            style={besideContent ? styles.panelBeside : styles.panelBelow}
          >
            {aside}
          </View>
        )}
      </View>
    </ScrollView>
  );
}

/** Until its screen story ships, deep links fail safely without exposing financial data. */
export function UnavailableScreen() {
  const router = useRouter();
  const intl = useIntl();
  const theme = useTheme();
  return (
    <ShellScreen
      title={intl.formatMessage({ id: 'route.unavailable.title' })}
      privacyToggle={false}
    >
      <ThemedText>{intl.formatMessage({ id: 'route.unavailable.description' })}</ThemedText>
      <Pressable
        accessibilityRole="button"
        style={[styles.button, { backgroundColor: theme.primary }]}
        onPress={() => router.replace('/overview')}
      >
        <ThemedText themeColor="onPrimary" style={styles.buttonLabel}>
          {intl.formatMessage({ id: 'nav.overview' })}
        </ThemedText>
      </Pressable>
    </ShellScreen>
  );
}

const styles = StyleSheet.create({
  scroll: { flexGrow: 1, alignItems: 'center', padding: 24 },
  // UX-02 / Figma M-screens: 16 px side padding on a phone.
  scrollPhone: { padding: 16 },
  frame: { width: '100%', maxWidth: 960, gap: 24 },
  frameWithPanel: { flexDirection: 'row', alignItems: 'flex-start', maxWidth: 960 + 24 + 360 },
  content: { flex: 1, minWidth: 0, gap: 20 },
  header: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  heading: { fontSize: 28, lineHeight: 36, flexShrink: 1 },
  panelBeside: { width: DETAIL_PANEL_WIDTH, flexShrink: 0 },
  panelBelow: { width: '100%' },
  button: {
    minHeight: 48,
    padding: 12,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'flex-start',
  },
  buttonLabel: { fontWeight: '700' },
});
