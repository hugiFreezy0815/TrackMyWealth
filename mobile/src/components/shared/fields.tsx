import { useEffect, useMemo, useState } from 'react';
import { Pressable, Text, TextInput, View, type TextInputProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

/**
 * Normalize local input into an API plain DecimalString, never a Number.
 * Accept either explicit native decimal separator or a grouping separator, not
 * mixed ambiguities. Input that is incomplete or invalid never reaches the API.
 */
export function parseAmountInput(input: string, locale: UiLocale): DecimalString | null {
  const trimmed = input.trim().replaceAll('\u00a0', '').replaceAll('\u202f', '');
  if (!trimmed || /[eE+]/.test(trimmed)) return null;
  const groups = locale === 'de-CH' ? /['’]/g : locale === 'de-DE' ? /\./g : /,/g;
  const decimalSeparator = locale === 'de-DE' ? ',' : '.';
  // Strictly validate grouping before stripping it. This prevents silent amount changes.
  const separator = locale === 'de-CH' ? "['’]" : locale === 'de-DE' ? '\\.' : ',';
  const decimal = locale === 'de-DE' ? ',' : '\\.';
  const validGrouping = new RegExp('^-?(?:\\d+|\\d{1,3}(?:' + separator + '\\d{3})+)(?:' + decimal + '\\d+)?$');
  if (!validGrouping.test(trimmed)) return null;
  const grouped = trimmed.replace(groups, '');
  const canonical = decimalSeparator === '.' ? grouped : grouped.replace(',', '.');
  const isValid = /^-?\d+(\.\d+)?$/.test(canonical);
  if (!isValid) return null;
  const rawInt = canonical.startsWith('-') ? canonical.slice(1).split('.')[0] : canonical.split('.')[0];
  const sign = canonical.startsWith('-') ? '-' : '';
  const rest = canonical.split('.')[1];
  const normalizedInt = rawInt.replace(/^0+(?=\d)/, '');
  return sign + normalizedInt + (rest == null ? '' : '.' + rest);
}

export type TextFieldProps = Omit<TextInputProps, 'style'> & {
  label: string;
  error?: string;
  hint?: string;
  testID?: string;
  locale?: UiLocale;
};
export function TextField({ label, error, hint, editable = true, locale = currentUiLocale(), testID, ...rest }: TextFieldProps) {
  const theme = useTheme();
  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ color: theme.textPrimary, fontSize: 14, fontWeight: '500' }}>{label}</Text>
      <TextInput
        testID={testID}
        accessibilityLabel={label}
        accessibilityLanguage={locale}
        accessibilityHint={error ?? hint}
        editable={editable}
        placeholderTextColor={theme.textTertiary}
        style={{
          minHeight: 44, borderColor: error ? theme.statusLoss : theme.borderInteractive,
          borderWidth: 1, borderRadius: Radii.control, paddingHorizontal: Spacing.compact,
          color: theme.textPrimary, backgroundColor: theme.bgSurface,
        }}
        {...rest}
      />
      {hint && !error ? <Text style={{ fontSize: 12, color: theme.textSecondary }}>{hint}</Text> : null}
      {error ? <Text accessibilityRole="alert" style={{ fontSize: 12, color: theme.statusLoss }}>{error}</Text> : null}
    </View>
  );
}

export type AmountFieldProps = Omit<TextFieldProps, 'value' | 'onChangeText' | 'keyboardType'> & {
  value: string;
  onChangeValue: (value: DecimalString | null) => void;
  currency: string;
  onChangeRawText?: (raw: string) => void;
};
/** Controlled display input. Emit a valid DecimalString or null (never a rounded float). */
export function AmountField({ value, onChangeValue, onChangeRawText, currency, locale = currentUiLocale(), ...props }: AmountFieldProps) {
  const [raw, setRaw] = useState(value);
  useEffect(() => setRaw(value), [value]);
  const [touched, setTouched] = useState(false);
  const normalized = parseAmountInput(raw, locale);
  return (
    <TextField
      {...props}
      locale={locale}
      value={raw}
      keyboardType="decimal-pad"
      autoComplete="off"
      onBlur={(event) => { setTouched(true); props.onBlur?.(event); }}
      onChangeText={(next) => {
        setRaw(next);
        onChangeRawText?.(next);
        onChangeValue(parseAmountInput(next, locale));
      }}
      hint={currency}
      error={props.error ?? (touched && raw && normalized === null ? uiMessage('ui.invalidAmount', locale) : undefined)}
    />
  );
}

export type PickerOption = { value: string; label: string };
export function CurrencyPicker({
  label, value, options, onChange, locale = currentUiLocale(), disabled = false,
}: { label: string; value: string | null; options: PickerOption[]; onChange: (value: string) => void; locale?: UiLocale; disabled?: boolean }) {
  const theme = useTheme();
  return (
    <View style={{ gap: Spacing.two }}>
      <Text style={{ color: theme.textPrimary, fontWeight: '500' }}>{label}</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
        {options.map((option) => (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{ selected: value === option.value, disabled }}
            accessibilityLabel={option.label + (value === option.value ? '. ' + uiMessage('ui.selected', locale) : '')}
            disabled={disabled}
            onPress={() => onChange(option.value)}
            style={{
              minHeight: 44, minWidth: 52, borderRadius: Radii.control, padding: Spacing.two,
              justifyContent: 'center', alignItems: 'center',
              borderWidth: 1, borderColor: theme.borderInteractive,
              backgroundColor: value === option.value ? theme.brandSubtle : theme.bgSurface,
            }}
          >
            <Text style={{ color: theme.textPrimary }}>
              {option.label}{value === option.value ? ' ✓' : ''}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

export function isValidIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() + 1 === month && date.getUTCDate() === day;
}
export function DateField({
  value, onChange, onChangeRawText, label, locale = currentUiLocale(), ...props
}: Omit<TextFieldProps, 'value' | 'onChangeText'> & {
  value: string; onChange: (value: string | null) => void; onChangeRawText?: (value: string) => void;
}) {
  const [raw, setRaw] = useState(value);
  useEffect(() => setRaw(value), [value]);
  const valid = useMemo(() => !raw || isValidIsoDate(raw), [raw]);
  return (
    <TextField
      {...props}
      label={label}
      locale={locale}
      value={raw}
      placeholder="YYYY-MM-DD"
      keyboardType="numbers-and-punctuation"
      onChangeText={(input) => { setRaw(input); onChangeRawText?.(input); onChange(isValidIsoDate(input) ? input : null); }}
      error={props.error ?? (!valid ? uiMessage('ui.invalidDate', locale) : undefined)}
    />
  );
}
