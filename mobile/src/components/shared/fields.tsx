import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Pressable, Text, TextInput, View, type TextInputProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

/** Number input conventions (#378): de-CH 1'234.5, other German 1.234,5, English 1,234.5. */
function inputConvention(locale: string): { group: string; decimal: ',' | '.' } {
  const lower = locale.toLowerCase();
  if (lower.startsWith('de-ch')) return { group: "['’]", decimal: '.' };
  if (lower.startsWith('de')) return { group: '\\.', decimal: ',' };
  return { group: ',', decimal: '.' };
}

/**
 * Normalize local input into an API plain DecimalString, never a Number.
 * Accept either explicit native decimal separator or a grouping separator, not
 * mixed ambiguities. Input that is incomplete or invalid never reaches the API.
 */
export function parseAmountInput(input: string, locale: string): DecimalString | null {
  const trimmed = input
    .trim()
    .replaceAll(' ', '')
    .replaceAll(' ', '')
    // Accept the typographic minus that `Amount` displays, so a copied amount parses.
    .replace(/^−/, '-');
  if (!trimmed || /[eE+]/.test(trimmed)) return null;
  const { group, decimal } = inputConvention(locale);
  // Strictly validate grouping before stripping it. This prevents silent amount changes.
  const validGrouping = new RegExp(
    '^-?(?:\\d+|\\d{1,3}(?:' + group + '\\d{3})+)(?:' + (decimal === ',' ? ',' : '\\.') + '\\d+)?$',
  );
  if (!validGrouping.test(trimmed)) return null;
  const grouped = trimmed.replace(new RegExp(group, 'g'), '');
  const canonical = decimal === '.' ? grouped : grouped.replace(',', '.');
  if (!/^-?\d+(\.\d+)?$/.test(canonical)) return null;
  const negative = canonical.startsWith('-');
  const [rawInt, rest] = (negative ? canonical.slice(1) : canonical).split('.');
  return (negative ? '-' : '') + rawInt.replace(/^0+(?=\d)/, '') + (rest == null ? '' : '.' + rest);
}

/** The editable text for a DecimalString: the locale's decimal separator, no grouping. */
export function amountInputText(value: DecimalString | null, locale: string): string {
  if (value == null) return '';
  return inputConvention(locale).decimal === ',' ? value.replace('.', ',') : value;
}

/**
 * What a controlled field shows: the member's own text while it still stands for the current
 * value, else the value from the parent (e.g. after a reset or a conflict reload). Derived on
 * every render, so no effect has to copy props into state.
 */
type Draft<T> = { text: string; base: T };
function shownText<T>(
  draft: Draft<T> | null,
  value: T,
  parse: (text: string) => T,
  format: (value: T) => string,
): string {
  if (draft && (draft.base === value || parse(draft.text) === value)) return draft.text;
  return format(value);
}

export type TextFieldProps = Omit<TextInputProps, 'style'> & {
  label: string;
  error?: string;
  hint?: string;
  testID?: string;
};
export function TextField({
  label,
  error,
  hint,
  editable = true,
  testID,
  ...rest
}: TextFieldProps) {
  const theme = useTheme();
  const intl = useIntl();
  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <TextInput
        testID={testID}
        accessibilityLabel={label}
        accessibilityLanguage={intl.locale}
        accessibilityHint={error ?? hint}
        editable={editable}
        placeholderTextColor={theme.textTertiary}
        style={{
          ...TypeScale.bodyLarge,
          minHeight: 44,
          borderColor: error ? theme.statusLoss : theme.borderInteractive,
          borderWidth: 1,
          borderRadius: Radii.control,
          paddingHorizontal: Spacing.compact,
          color: theme.textPrimary,
          backgroundColor: theme.bgSurface,
        }}
        {...rest}
      />
      <FieldMessages hint={hint} error={error} />
    </View>
  );
}

/** The hint under a field, or its error (announced as an alert) in place of the hint. */
export function FieldMessages({ hint, error }: { hint?: string; error?: string }) {
  const theme = useTheme();
  if (error) {
    return (
      <Text accessibilityRole="alert" style={{ ...TypeScale.caption, color: theme.statusLoss }}>
        {error}
      </Text>
    );
  }
  return hint ? (
    <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{hint}</Text>
  ) : null;
}

export type AmountFieldProps = Omit<TextFieldProps, 'value' | 'onChangeText' | 'keyboardType'> & {
  /** The current amount as a DecimalString (null when empty or invalid). */
  value: DecimalString | null;
  onChangeValue: (value: DecimalString | null) => void;
  currency: string;
  onChangeRawText?: (raw: string) => void;
};
/** Controlled by a DecimalString. Emits a valid DecimalString or null (never a rounded float). */
export function AmountField({
  value,
  onChangeValue,
  onChangeRawText,
  currency,
  hint,
  ...props
}: AmountFieldProps) {
  const intl = useIntl();
  const [draft, setDraft] = useState<Draft<DecimalString | null> | null>(null);
  const [touched, setTouched] = useState(false);
  const parse = (text: string) => parseAmountInput(text, intl.locale);
  const raw = shownText(draft, value, parse, (current) => amountInputText(current, intl.locale));
  const invalid = raw.trim() !== '' && parse(raw) === null;
  return (
    <TextField
      {...props}
      value={raw}
      keyboardType="decimal-pad"
      autoComplete="off"
      onBlur={(event) => {
        setTouched(true);
        props.onBlur?.(event);
      }}
      onChangeText={(next) => {
        setDraft({ text: next, base: value });
        onChangeRawText?.(next);
        onChangeValue(parse(next));
      }}
      hint={hint ? currency + ' · ' + hint : currency}
      error={
        props.error ??
        (touched && invalid ? intl.formatMessage({ id: 'ui.invalidAmount' }) : undefined)
      }
    />
  );
}

export type PickerOption = { value: string; label: string };
export function CurrencyPicker({
  label,
  value,
  options,
  onChange,
  disabled = false,
}: {
  label: string;
  value: string | null;
  options: PickerOption[];
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  const theme = useTheme();
  return (
    <View style={{ gap: Spacing.two }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <View
        accessibilityRole="radiogroup"
        accessibilityLabel={label}
        style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.two }}
      >
        {options.map((option) => {
          const checked = value === option.value;
          return (
            <Pressable
              key={option.value}
              accessibilityRole="radio"
              accessibilityState={{ checked, disabled }}
              accessibilityLabel={option.label}
              disabled={disabled}
              onPress={() => onChange(option.value)}
              style={{
                minHeight: 44,
                minWidth: 52,
                borderRadius: Radii.control,
                padding: Spacing.two,
                justifyContent: 'center',
                alignItems: 'center',
                borderWidth: 1,
                borderColor: theme.borderInteractive,
                backgroundColor: checked ? theme.brandSubtle : theme.bgSurface,
              }}
            >
              <Text style={{ ...TypeScale.bodyMedium, color: theme.textPrimary }}>
                {option.label}
                {checked ? ' ✓' : ''}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

export function isValidIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() + 1 === month && date.getUTCDate() === day
  );
}
