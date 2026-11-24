import type { ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';

import { Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

/** A liability card. `action` (for example "See all") sits at the end of the title row. */
export function LiabilityPanel({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  const theme = useTheme();
  return (
    <View
      style={{
        gap: Spacing.two,
        padding: Spacing.three,
        borderRadius: 16,
        backgroundColor: theme.bgSurface,
      }}
    >
      <View
        style={{
          flexDirection: 'row',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          columnGap: Spacing.two,
        }}
      >
        <Text
          accessibilityRole="header"
          style={{ ...TypeScale.headingSmall, color: theme.textPrimary, flexShrink: 1 }}
        >
          {title}
        </Text>
        {action}
      </View>
      {children}
    </View>
  );
}

export function LiabilityLink({
  label,
  onPress,
  expanded,
}: {
  label: string;
  onPress: () => void;
  expanded?: boolean;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      aria-expanded={expanded}
      onPress={onPress}
      style={{ minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' }}
    >
      <Text style={{ ...TypeScale.labelMedium, color: theme.brandLink }}>{label}</Text>
    </Pressable>
  );
}

export function LiabilityBadge({ label, tone }: { label: string; tone: 'warning' | 'success' }) {
  const theme = useTheme();
  return (
    <Text
      style={{
        ...TypeScale.labelMedium,
        color: tone === 'success' ? theme.statusGain : theme.statusWarning,
        backgroundColor: tone === 'success' ? theme.statusGainSubtle : theme.statusWarningSubtle,
        padding: Spacing.one,
        borderRadius: 8,
        alignSelf: 'flex-start',
      }}
    >
      {label}
    </Text>
  );
}
