import { useState } from 'react';
import { useIntl } from 'react-intl';
import { FlatList, Pressable, Text, View } from 'react-native';

import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { BottomSheet } from './feedback';
import { FieldMessages, TextField, type PickerOption } from './fields';

/** Case- and accent-insensitive, so "zur" finds "Zürich" and "franken" finds "Franken". */
function folded(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** The options whose label or value contains `query`; all of them for an empty query. */
export function filterOptions(options: readonly PickerOption[], query: string): PickerOption[] {
  const needle = folded(query.trim());
  if (!needle) return [...options];
  return options.filter(
    (option) => folded(option.label).includes(needle) || folded(option.value).includes(needle),
  );
}

export type SelectFieldProps = {
  label: string;
  value: string | null;
  options: readonly PickerOption[];
  onChange: (value: string) => void;
  disabled?: boolean;
  error?: string;
  hint?: string;
  testID?: string;
};

/**
 * A single choice from a long list (M14 "EUR · Euro ⌄"): the field shows the current choice and
 * opens a bottom sheet with a search box and the options as a radio list. Picking one closes it.
 */
export function SelectField({
  label,
  value,
  options,
  onChange,
  disabled = false,
  error,
  hint,
  testID,
}: SelectFieldProps) {
  const theme = useTheme();
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const current = options.find((option) => option.value === value);
  const shown = current?.label ?? value ?? intl.formatMessage({ id: 'select.none' });
  const visible = filterOptions(options, query);
  const close = () => {
    setOpen(false);
    setQuery('');
  };
  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <Pressable
        testID={testID}
        accessibilityRole="button"
        accessibilityLabel={`${label}: ${shown}`}
        accessibilityHint={error ?? hint}
        aria-disabled={disabled}
        disabled={disabled}
        onPress={() => setOpen(true)}
        style={{
          minHeight: 44,
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: Spacing.two,
          borderColor: error ? theme.statusLoss : theme.borderInteractive,
          borderWidth: 1,
          borderRadius: Radii.control,
          paddingHorizontal: Spacing.compact,
          backgroundColor: theme.bgSurface,
          opacity: disabled ? 0.5 : 1,
        }}
      >
        <Text
          numberOfLines={1}
          style={{ ...TypeScale.bodyLarge, color: theme.textPrimary, flexShrink: 1 }}
        >
          {shown}
        </Text>
        <Text
          accessibilityElementsHidden
          importantForAccessibility="no"
          style={{ ...TypeScale.bodyLarge, color: theme.textSecondary }}
        >
          ⌄
        </Text>
      </Pressable>
      <FieldMessages hint={hint} error={error} />
      <BottomSheet title={label} visible={open} onClose={close}>
        <TextField
          label={intl.formatMessage({ id: 'select.search' })}
          value={query}
          onChangeText={setQuery}
          autoCapitalize="none"
          autoCorrect={false}
        />
        {visible.length === 0 ? (
          <Text style={{ ...TypeScale.bodyMedium, color: theme.textSecondary }}>
            {intl.formatMessage({ id: 'select.noMatch' }, { query: query.trim() })}
          </Text>
        ) : null}
        <FlatList
          data={visible}
          keyExtractor={(option) => option.value}
          keyboardShouldPersistTaps="handled"
          accessibilityRole="radiogroup"
          accessibilityLabel={label}
          style={{ maxHeight: 360 }}
          initialNumToRender={20}
          renderItem={({ item }) => {
            const selected = item.value === value;
            return (
              <Pressable
                accessibilityRole="radio"
                aria-checked={selected}
                accessibilityLabel={item.label}
                onPress={() => {
                  onChange(item.value);
                  close();
                }}
                style={{
                  minHeight: 44,
                  justifyContent: 'center',
                  paddingHorizontal: Spacing.compact,
                  borderRadius: Radii.control,
                  backgroundColor: selected ? theme.brandSubtle : 'transparent',
                }}
              >
                <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
                  {item.label}
                  {selected ? ' ✓' : ''}
                </Text>
              </Pressable>
            );
          }}
        />
      </BottomSheet>
    </View>
  );
}
