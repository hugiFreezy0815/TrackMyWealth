import { useState } from 'react';
import { useIntl } from 'react-intl';
import { Text, TextInput, View, type TextInputProps } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { Radii, Spacing, TypeScale } from '@/constants/theme';
import { useTheme } from '@/hooks/use-theme';
import { DEVICE_DECIMAL_SEPARATOR } from '@/i18n/device-intl';

import { ChipGroup, type ChipOption } from './presentation';

/** Number input conventions (#378): de-CH 1'234.5, other German 1.234,5, English 1,234.5. */
function inputConvention(locale: string): { group: ',' | '.' | "'"; decimal: ',' | '.' } {
  const lower = locale.toLowerCase();
  if (lower.startsWith('de-ch')) return { group: "'", decimal: '.' };
  if (lower.startsWith('de')) return { group: '.', decimal: ',' };
  return { group: ',', decimal: '.' };
}

/**
 * Normalize typed or pasted input into an API plain DecimalString, never a Number.
 *
 * The `decimal-pad` keyboard offers the device's decimal key, which can differ from the app
 * language (#534): a Swiss phone with the app in German offers ".", an English app on a German
 * phone offers ",". So the last separator is the decimal point when it occurs once and is the
 * decimal separator of the app locale or of the device; every other separator must group digits
 * in threes with the locale's grouping character (apostrophes always group). A lone separator
 * that could be either ("1.250" when "." both groups and is the device's decimal key) is
 * rejected rather than guessed: an amount must never change silently. Invalid input is null.
 */
export function parseAmountInput(
  input: string,
  locale: string,
  deviceDecimal: ',' | '.' = DEVICE_DECIMAL_SEPARATOR,
): DecimalString | null {
  const text = input
    .trim()
    .replaceAll('\u00A0', '')
    .replaceAll('\u202F', '')
    .replaceAll(' ', '')
    .replaceAll('’', "'")
    // Accept the typographic minus that `Amount` displays, so a copied amount parses.
    .replace(/^−/, '-');
  if (!/^-?\d[\d.,']*$/.test(text)) return null;
  const negative = text.startsWith('-');
  const body = negative ? text.slice(1) : text;
  const convention = inputConvention(locale);
  const groups = new Set<string>([convention.group, "'"]);
  const separators = body.replace(/\d/g, '');
  const last = separators.at(-1);
  const decimal =
    last &&
    (last === convention.decimal || last === deviceDecimal) &&
    separators.indexOf(last) === separators.length - 1
      ? last
      : null;
  const at = decimal ? body.lastIndexOf(decimal) : body.length;
  const integer = body.slice(0, at);
  const fraction = decimal ? body.slice(at + 1) : null;
  if (fraction !== null && !/^\d+$/.test(fraction)) return null;
  const grouping = new Set(integer.replace(/\d/g, ''));
  if (grouping.size > 1) return null;
  const [group] = grouping;
  if (group && (!groups.has(group) || !/^\d{1,3}(?:\D\d{3})+$/.test(integer))) return null;
  // "1.250": a decimal point to one convention and a thousands group to the other.
  if (decimal && groups.has(decimal) && !group && integer.length <= 3 && fraction?.length === 3) {
    return null;
  }
  const digits = integer.replace(/\D/g, '').replace(/^0+(?=\d)/, '');
  return (negative ? '-' : '') + digits + (fraction === null ? '' : '.' + fraction);
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
  /**
   * The parsed amount, and whether the text is invalid. Both an empty and an invalid field emit
   * null, so a form must block submitting while `invalid` is true instead of saving "no amount".
   */
  onChangeValue: (value: DecimalString | null, state: { invalid: boolean }) => void;
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
        const parsed = parse(next);
        onChangeValue(parsed, { invalid: next.trim() !== '' && parsed === null });
      }}
      hint={hint ? currency + ' · ' + hint : currency}
      error={
        props.error ??
        (touched && invalid ? intl.formatMessage({ id: 'ui.invalidAmount' }) : undefined)
      }
    />
  );
}

export type PickerOption = ChipOption;
/** A single choice among currencies: a labelled radio group of chips (`ChipGroup`). */
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
      <ChipGroup
        label={label}
        options={options}
        selected={value == null ? [] : [value]}
        onSelect={onChange}
        disabled={disabled}
      />
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
