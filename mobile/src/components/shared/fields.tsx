import { useState } from 'react';
import { Pressable, Text, TextInput, View, type TextInputProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

/**
 * Normalize local input into an API plain DecimalString, never a Number.
 * Accept either explicit native decimal separator or a grouping separator, not
 * mixed ambiguities. Input that is incomplete or invalid never reaches the API.
 */
export function parseAmountInput(input: string, locale: UiLocale): DecimalString | null {
  const trimmed = input
    .trim()
    .replaceAll(' ', '')
    .replaceAll(' ', '')
    // Accept the typographic minus that `Amount` displays, so a copied amount parses.
    .replace(/^−/, '-');
  if (!trimmed || /[eE+]/.test(trimmed)) return null;
  const groups = locale === 'de-CH' ? /['’]/g : locale === 'de-DE' ? /\./g : /,/g;
  const decimalSeparator = locale === 'de-DE' ? ',' : '.';
  // Strictly validate grouping before stripping it. This prevents silent amount changes.
  const separator = locale === 'de-CH' ? "['’]" : locale === 'de-DE' ? '\\.' : ',';
  const decimal = locale === 'de-DE' ? ',' : '\\.';
  const validGrouping = new RegExp(
    '^-?(?:\\d+|\\d{1,3}(?:' + separator + '\\d{3})+)(?:' + decimal + '\\d+)?$',
  );
  if (!validGrouping.test(trimmed)) return null;
  const grouped = trimmed.replace(groups, '');
  const canonical = decimalSeparator === '.' ? grouped : grouped.replace(',', '.');
  if (!/^-?\d+(\.\d+)?$/.test(canonical)) return null;
  const negative = canonical.startsWith('-');
  const [rawInt, rest] = (negative ? canonical.slice(1) : canonical).split('.');
  return (negative ? '-' : '') + rawInt.replace(/^0+(?=\d)/, '') + (rest == null ? '' : '.' + rest);
}

/** The editable text for a DecimalString: the locale's decimal separator, no grouping. */
export function amountInputText(value: DecimalString | null, locale: UiLocale): string {
  if (value == null) return '';
  return locale === 'de-DE' ? value.replace('.', ',') : value;
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
  locale?: UiLocale;
};
export function TextField({
  label,
  error,
  hint,
  editable = true,
  locale = currentUiLocale(),
  testID,
  ...rest
}: TextFieldProps) {
  const theme = useTheme();
  return (
    <View style={{ gap: Spacing.one }}>
      <Text style={{ ...TypeScale.labelMedium, color: theme.textPrimary }}>{label}</Text>
      <TextInput
        testID={testID}
        accessibilityLabel={label}
        accessibilityLanguage={locale}
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
      {hint && !error ? (
        <Text style={{ ...TypeScale.caption, color: theme.textSecondary }}>{hint}</Text>
      ) : null}
      {error ? (
        <Text accessibilityRole="alert" style={{ ...TypeScale.caption, color: theme.statusLoss }}>
          {error}
        </Text>
      ) : null}
    </View>
  );
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
  locale = currentUiLocale(),
  ...props
}: AmountFieldProps) {
  const [draft, setDraft] = useState<Draft<DecimalString | null> | null>(null);
  const [touched, setTouched] = useState(false);
  const parse = (text: string) => parseAmountInput(text, locale);
  const raw = shownText(draft, value, parse, (current) => amountInputText(current, locale));
  const invalid = raw.trim() !== '' && parse(raw) === null;
  return (
    <TextField
      {...props}
      locale={locale}
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
        props.error ?? (touched && invalid ? uiMessage('ui.invalidAmount', locale) : undefined)
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

/** Controlled by an ISO date (`YYYY-MM-DD`, or null when empty or invalid). */
export function DateField({
  value,
  onChange,
  onChangeRawText,
  label,
  locale = currentUiLocale(),
  ...props
}: Omit<TextFieldProps, 'value' | 'onChangeText' | 'onChange'> & {
  value: string | null;
  onChange: (value: string | null) => void;
  onChangeRawText?: (value: string) => void;
}) {
  const [draft, setDraft] = useState<Draft<string | null> | null>(null);
  const current = value || null;
  const parse = (text: string) => (isValidIsoDate(text) ? text : null);
  const raw = shownText(draft, current, parse, (shown) => shown ?? '');
  // Invalid text stays in the field after blur, with its error, until the member corrects it.
  const invalid = raw !== '' && !isValidIsoDate(raw);
  return (
    <TextField
      {...props}
      label={label}
      locale={locale}
      value={raw}
      placeholder={uiMessage('ui.datePlaceholder', locale)}
      keyboardType="numbers-and-punctuation"
      onChangeText={(input) => {
        setDraft({ text: input, base: current });
        onChangeRawText?.(input);
        onChange(parse(input));
      }}
      error={props.error ?? (invalid ? uiMessage('ui.invalidDate', locale) : undefined)}
    />
  );
}
