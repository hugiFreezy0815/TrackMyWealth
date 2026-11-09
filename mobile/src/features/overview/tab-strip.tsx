import type { ReactNode } from 'react';
import type { IntlShape } from 'react-intl';
import { Platform, Pressable, ScrollView, Text, View } from 'react-native';

import type { components } from '@/api/schema';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { msg } from './overview-text';

const MAX_UNTRUNCATED = 24;
type Portfolio = components['schemas']['PortfolioListItemResponse'];

/** Tabs are navigation: the selected portfolio renders S2; the first tab renders S1. */
export function TabStrip({
  name, intl, offline, onAddPortfolio, portfolios = [], selectedId = null,
  onOverview, onPortfolio,
}: {
  name: string | null;
  intl: IntlShape;
  offline: boolean;
  onAddPortfolio: () => void;
  portfolios?: readonly Portfolio[];
  selectedId?: string | null;
  onOverview?: () => void;
  onPortfolio?: (id: string) => void;
}) {
  const theme = useTheme();
  const label = name || msg(intl, 'overview.tab.allPortfolios');
  const tab = (text: string, selected: boolean, onPress?: () => void, key?: string): ReactNode => {
    const style = {
      minHeight: 44, maxWidth: 256, paddingHorizontal: 16, justifyContent: 'center',
      borderRadius: Radii.pill,
      backgroundColor: selected ? theme.bgInverse : theme.bgSurface,
      borderWidth: selected ? 0 : 1,
      borderColor: theme.borderInteractive,
    } as const;
    return onPress ? (
      <Pressable key={key} accessibilityRole="tab"
        accessibilityLabel={text} accessibilityState={{ selected }}
        aria-selected={selected} onPress={onPress} style={style}>
        <Text numberOfLines={text.length > MAX_UNTRUNCATED ? 1 : undefined}
          style={{ ...TypeScale.labelMedium, color: selected ? theme.bgSurface : theme.textPrimary }}>
          {text}
        </Text>
      </Pressable>
    ) : (
      <View key={key} accessible accessibilityRole="tab"
        accessibilityState={{ selected }} aria-selected={selected}
        accessibilityLabel={text} style={style}>
        <Text numberOfLines={text.length > MAX_UNTRUNCATED ? 1 : undefined}
          style={{ ...TypeScale.labelMedium, color: selected ? theme.bgSurface : theme.textPrimary }}>
          {text}
        </Text>
      </View>
    );
  };
  const tabs: ReactNode = (
    <>
      {tab(label, selectedId == null, onOverview, 'all')}
      {portfolios.map((item) => tab(item.name, item.id === selectedId,
        onPortfolio ? () => onPortfolio(item.id) : undefined, item.id))}
      <Pressable accessibilityRole="tab"
        accessibilityState={{ selected: false, disabled: offline }}
        aria-selected={false} aria-disabled={offline}
        disabled={offline} onPress={onAddPortfolio}
        style={{ minHeight: 44, justifyContent: 'center', paddingHorizontal: 16,
          borderRadius: Radii.pill, borderWidth: 1, borderColor: theme.borderInteractive,
          backgroundColor: theme.bgSurface, opacity: offline ? 0.5 : 1 }}>
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {msg(intl, 'overview.tab.addPortfolio')}
        </Text>
      </Pressable>
    </>
  );
  if (Platform.OS === 'web') {
    return <View testID="overview-tabs" accessibilityRole="tablist"
      style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}>{tabs}</View>;
  }
  return <ScrollView testID="overview-tabs" horizontal
    showsHorizontalScrollIndicator={false} accessibilityRole="tablist"
    contentContainerStyle={{ flexDirection: 'row', gap: Spacing.two }}>
    {tabs}
  </ScrollView>;
}
