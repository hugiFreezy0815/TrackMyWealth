import type { ReactNode } from 'react';
import type { IntlShape } from 'react-intl';
import { Platform, Pressable, ScrollView, Text, View } from 'react-native';

import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { msg } from './overview-text';

const MAX_UNTRUNCATED = 24;

/**
 * S1 `PortfolioTabs`. Until the Portfolio View (US-04-12) merges, only the first tab and
 * "+ Add portfolio" show. Phone: the strip scrolls horizontally; web: it wraps.
 */
export function TabStrip({
  name,
  intl,
  offline,
  onAddPortfolio,
}: {
  name: string | null;
  intl: IntlShape;
  offline: boolean;
  onAddPortfolio: () => void;
}) {
  const theme = useTheme();
  const label = name || msg(intl, 'overview.tab.allPortfolios');
  const tabs: ReactNode = (
    <>
      <View
        accessible
        accessibilityRole="tab"
        accessibilityState={{ selected: true }}
        // react-native-web ignores accessibilityState; RN maps aria-* back on native.
        aria-selected
        accessibilityLabel={label}
        style={{
          minHeight: 44,
          maxWidth: 256,
          paddingHorizontal: 16,
          justifyContent: 'center',
          borderRadius: Radii.pill,
          backgroundColor: theme.bgInverse,
        }}
      >
        <Text
          numberOfLines={label.length > MAX_UNTRUNCATED ? 1 : undefined}
          style={{ ...TypeScale.labelMedium, color: theme.bgSurface }}
        >
          {label}
        </Text>
      </View>
      {/* The spec's last tab: a tablist may only hold tabs, so it is an unselected tab that
          starts the add-portfolio flow instead of switching content. */}
      <Pressable
        accessibilityRole="tab"
        accessibilityState={{ selected: false, disabled: offline }}
        aria-selected={false}
        aria-disabled={offline}
        disabled={offline}
        onPress={onAddPortfolio}
        style={{
          minHeight: 44,
          justifyContent: 'center',
          paddingHorizontal: 16,
          borderRadius: Radii.pill,
          borderWidth: 1,
          borderColor: theme.borderInteractive,
          backgroundColor: theme.bgSurface,
          opacity: offline ? 0.5 : 1,
        }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {msg(intl, 'overview.tab.addPortfolio')}
        </Text>
      </Pressable>
    </>
  );

  if (Platform.OS === 'web') {
    return (
      <View
        testID="overview-tabs"
        accessibilityRole="tablist"
        style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}
      >
        {tabs}
      </View>
    );
  }
  return (
    <ScrollView
      testID="overview-tabs"
      horizontal
      showsHorizontalScrollIndicator={false}
      accessibilityRole="tablist"
      contentContainerStyle={{ flexDirection: 'row', gap: Spacing.two }}
    >
      {tabs}
    </ScrollView>
  );
}
