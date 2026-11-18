import { Pressable, ScrollView, Text } from 'react-native';

import { Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

export type CategoryOptionsProps = {
  label: string;
  choices: { id: string; label: string }[];
  selected: string | null;
  disabled: boolean;
  onSelect: (id: string) => void;
};

export function CategoryOptions({
  label,
  choices,
  selected,
  disabled,
  onSelect,
}: CategoryOptionsProps) {
  const theme = useTheme();
  return (
    <ScrollView
      accessibilityRole="radiogroup"
      accessibilityLabel={label}
      keyboardShouldPersistTaps="handled"
      style={{ maxHeight: 360 }}
      contentContainerStyle={{ gap: Spacing.one }}
    >
      {choices.map((choice) => (
        <Pressable
          key={choice.id}
          accessibilityRole="radio"
          aria-checked={selected === choice.id}
          disabled={disabled}
          onPress={() => onSelect(choice.id)}
          accessibilityLabel={choice.label}
          style={{ minHeight: 44, justifyContent: 'center', paddingHorizontal: Spacing.two }}
        >
          <Text style={{ color: theme.textPrimary }}>
            {selected === choice.id ? '✓ ' : ''}
            {choice.label}
          </Text>
        </Pressable>
      ))}
    </ScrollView>
  );
}
