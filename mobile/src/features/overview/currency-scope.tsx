import { useState } from 'react';
import type { IntlShape } from 'react-intl';
import { Pressable, Text, View } from 'react-native';

import { ChipGroup } from '@/components/shared';
import { Radii, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { msg } from './overview-text';

/** S1 scope bar: currency only in sprint 7 (`/net-worth` has no account scope). */
export function CurrencyScope({
  currency,
  choices,
  choose,
  intl,
}: {
  currency: string;
  choices: string[];
  choose: (currency: string) => void;
  intl: IntlShape;
}) {
  const theme = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <View style={{ gap: 8 }}>
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        aria-expanded={expanded}
        accessibilityLabel={msg(intl, 'overview.scope.choose')}
        accessibilityHint={msg(intl, 'overview.scope', { currency })}
        onPress={() => setExpanded((previous) => !previous)}
        style={{
          minHeight: 44,
          alignSelf: 'flex-start',
          flexDirection: 'row',
          alignItems: 'center',
          gap: 6,
          paddingHorizontal: 16,
          borderRadius: Radii.pill,
          borderWidth: 1,
          borderColor: theme.borderInteractive,
          backgroundColor: theme.bgSurface,
        }}
      >
        <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>
          {msg(intl, 'overview.scope', { currency })}
        </Text>
        {/* Decorative chevron: the button's label and expanded state carry the meaning. */}
        <Text
          aria-hidden
          accessibilityElementsHidden
          importantForAccessibility="no"
          style={{ ...TypeScale.labelMedium, color: theme.textSecondary }}
        >
          {expanded ? '⌃' : '⌄'}
        </Text>
      </Pressable>
      {expanded ? (
        <ChipGroup
          label={msg(intl, 'overview.scope.choose')}
          options={choices.map((value) => ({ value, label: value }))}
          selected={[currency]}
          onSelect={(next) => {
            choose(next);
            setExpanded(false);
          }}
        />
      ) : null}
    </View>
  );
}
