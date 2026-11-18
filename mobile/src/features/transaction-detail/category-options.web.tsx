import { useId } from 'react';
import { Text, View } from 'react-native';

import { Spacing } from '@/constants/theme';
import { useColorScheme } from '@/hooks/use-color-scheme';
import { useTheme } from '@/hooks/use-theme';
import type { CategoryOptionsProps } from './category-options';

export function CategoryOptions({
  label,
  choices,
  selected,
  disabled,
  onSelect,
}: CategoryOptionsProps) {
  const name = useId();
  const theme = useTheme();
  // Native radios follow the browser's light scheme and accent unless told otherwise.
  const radioStyle = {
    accentColor: theme.brandPrimary,
    colorScheme: useColorScheme() === 'dark' ? 'dark' : 'light',
  };
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={label} style={{ gap: Spacing.one }}>
      {choices.map((choice) => (
        <label
          key={choice.id}
          style={{ display: 'flex', alignItems: 'center', minHeight: 44, gap: Spacing.two }}
        >
          <input
            type="radio"
            name={name}
            checked={selected === choice.id}
            disabled={disabled}
            onChange={() => onSelect(choice.id)}
            style={radioStyle}
          />
          <Text style={{ color: theme.textPrimary }}>{choice.label}</Text>
        </label>
      ))}
    </View>
  );
}
