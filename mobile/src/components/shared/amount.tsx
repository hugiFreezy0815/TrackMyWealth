import { Text, type TextStyle } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

const DECIMAL = /^-?\d+(\.\d+)?$/;
const NBSP = '\u00a0';
export type SignRole = 'balance' | 'change';
export type AmountFormatOptions = {
  value: DecimalString | null;
  currency: string;
  locale?: UiLocale;
  signRole?: SignRole;
  approx?: boolean;
  masked?: boolean;
  valueKnown?: boolean;
};
/**
 * UX-03 normative amount format, including currency code prefix and commercial rounding.
 * Intl NumberFormat receives the decimal *string*, never a JS floating-point number.
 * This helper is presentation-only. Send the original DecimalString to the API.
 */
export function displayAmount({
  value,
  currency,
  locale = 'en',
  signRole = 'balance',
  approx = false,
  masked = false,
  valueKnown = true,
}: AmountFormatOptions): string {
  if (masked) return '••••';
  if (!valueKnown || value == null) return '–';
  if (typeof value !== 'string' || !DECIMAL.test(value)) throw new TypeError('Not a DecimalString');
  const scale = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
    .maximumFractionDigits;
  const negative = value.startsWith('-');
  const parts = new Intl.NumberFormat(locale, {
    style: 'decimal',
    minimumFractionDigits: scale,
    maximumFractionDigits: scale,
    roundingMode: 'halfExpand',
    useGrouping: true,
  }).formatToParts((negative ? value.slice(1) : value) as `${number}`);
  const zero = parts.every(
    (part) => !['integer', 'fraction'].includes(part.type) || /^0+$/.test(part.value),
  );
  const number = parts
    .map((part) => (part.type === 'group' && locale === 'de-CH' ? "'" : part.value))
    .join('');
  const sign = zero ? '' : negative ? '−' : signRole === 'change' ? '+' : '';
  return (approx ? '≈' : '') + sign + currency + NBSP + number;
}

export function accessibleAmount({
  value,
  currency,
  locale = 'en',
  masked = false,
  valueKnown = true,
  approx = false,
}: AmountFormatOptions): string {
  if (masked) return uiMessage('amount.hidden', locale);
  if (value == null || !valueKnown) return uiMessage('amount.unknown', locale);
  const formatted = displayAmount({ value, currency, locale });
  const negative = formatted.startsWith('−');
  const numericText = formatted.split(NBSP)[1] ?? '';
  // DisplayNames has localized currency names on web and supported native Intl runtimes.
  // The ISO code remains a safe fallback if the polyfill lacks that locale.
  let currencyName = currency;
  try {
    currencyName = new Intl.DisplayNames([locale], { type: 'currency' }).of(currency) ?? currency;
  } catch {
    // The offline/hermes locale data may not provide currency names.
  }
  const prefix = negative ? 'minus ' : '';
  const approximate = approx ? (locale.startsWith('de') ? 'ungefähr ' : 'approximately ') : '';
  return approximate + prefix + numericText + ' ' + currencyName;
}

export type AmountProps = AmountFormatOptions & {
  style?: TextStyle;
  accessibilityLabel?: string;
  testID?: string;
};
export function Amount({ style, accessibilityLabel, testID, ...options }: AmountProps) {
  const theme = useTheme();
  const locale = options.locale ?? currentUiLocale();
  const display = displayAmount({ ...options, locale });
  // Masking wins over any caller-provided label: private digits cannot leak.
  const label = options.masked
    ? uiMessage('amount.hidden', locale)
    : (accessibilityLabel ?? accessibleAmount({ ...options, locale }));
  return (
    <Text
      testID={testID}
      accessibilityLabel={label}
      style={[{ color: theme.textPrimary, fontVariant: ['tabular-nums'], flexShrink: 1 }, style]}
    >
      {display}
    </Text>
  );
}
