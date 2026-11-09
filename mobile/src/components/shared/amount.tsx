import { Text, type TextStyle } from 'react-native';

import type { DecimalString } from '@/api/decimal';
import { useTheme } from '@/hooks/use-theme';

import { currentUiLocale, uiMessage, type UiLocale } from './locale';

const DECIMAL = /^-?\d+(\.\d+)?$/;
const CURRENCY_CODE = /^[A-Z]{3}$/;
const NBSP = ' ';
const MASK = '••••';
const UNKNOWN = '–';

/** #378 normative separators per UI locale (`de-CH` 1'234.56, `de-DE` 1.234,56, `en` 1,234.56). */
const SEPARATORS: Record<UiLocale, { group: string; decimal: string }> = {
  en: { group: ',', decimal: '.' },
  'de-CH': { group: "'", decimal: '.' },
  'de-DE': { group: '.', decimal: ',' },
};

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

/** The ISO 4217 minor-unit count; only the currency code reaches Intl, never the amount. */
function currencyScale(currency: string): number {
  if (!CURRENCY_CODE.test(currency)) throw new TypeError('Not an ISO 4217 currency code');
  return (
    new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 2
  );
}

/** Add one to a string of decimal digits ("0999" → "1000"). */
function incrementDigits(digits: string): string {
  const chars = digits.split('');
  for (let i = chars.length - 1; i >= 0; i--) {
    if (chars[i] !== '9') {
      chars[i] = String(Number(chars[i]) + 1);
      return chars.join('');
    }
    chars[i] = '0';
  }
  return '1' + chars.join('');
}

/**
 * Commercial rounding (half away from zero) of an unsigned decimal string to `scale` digits,
 * done on the digits themselves: exact for any length, independent of the JS engine's Intl.
 */
function roundUnsigned(value: string, scale: number): { integer: string; fraction: string } {
  const [integer, fraction = ''] = value.split('.');
  if (fraction.length <= scale) return { integer, fraction: fraction.padEnd(scale, '0') };
  let digits = integer + fraction.slice(0, scale);
  if (fraction[scale] >= '5') digits = incrementDigits(digits);
  const integerLength = digits.length - scale;
  return { integer: digits.slice(0, integerLength), fraction: digits.slice(integerLength) };
}

/**
 * UX-03 normative amount format, including currency code prefix and commercial rounding.
 * The DecimalString is rounded and grouped as text, never converted to a JS number.
 * This helper is presentation-only. Send the original DecimalString to the API.
 * Throws a TypeError for a value that is not a DecimalString or a malformed currency code.
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
  if (masked) return MASK;
  if (!valueKnown || value == null) return UNKNOWN;
  if (typeof value !== 'string' || !DECIMAL.test(value)) throw new TypeError('Not a DecimalString');
  const scale = currencyScale(currency);
  const negative = value.startsWith('-');
  const rounded = roundUnsigned(negative ? value.slice(1) : value, scale);
  const integer = rounded.integer.replace(/^0+(?=\d)/, '');
  const zero = /^0+$/.test(integer + rounded.fraction);
  const { group, decimal } = SEPARATORS[locale] ?? SEPARATORS.en;
  const number =
    integer.replace(/\B(?=(\d{3})+(?!\d))/g, group) +
    (scale > 0 ? decimal + rounded.fraction : '');
  const sign = zero ? '' : negative ? '−' : signRole === 'change' ? '+' : '';
  return (approx ? '≈' : '') + sign + currency + NBSP + number;
}

export function accessibleAmount({
  value,
  currency,
  locale = 'en',
  signRole = 'balance',
  masked = false,
  valueKnown = true,
  approx = false,
}: AmountFormatOptions): string {
  if (masked) return uiMessage('amount.hidden', locale);
  if (value == null || !valueKnown) return uiMessage('amount.unknown', locale);
  const formatted = displayAmount({ value, currency, locale, signRole });
  const numericText = formatted.split(NBSP)[1] ?? '';
  // DisplayNames has localized currency names on web and supported native Intl runtimes.
  // The ISO code remains a safe fallback if the runtime lacks that locale.
  let currencyName = currency;
  try {
    currencyName = new Intl.DisplayNames([locale], { type: 'currency' }).of(currency) ?? currency;
  } catch {
    // The offline/hermes locale data may not provide currency names.
  }
  let text = numericText + ' ' + currencyName;
  if (formatted.startsWith('−')) text = uiMessage('amount.negative', locale, { amount: text });
  else if (formatted.startsWith('+')) text = uiMessage('amount.positive', locale, { amount: text });
  return approx ? uiMessage('amount.approximate', locale, { amount: text }) : text;
}

/**
 * Display and accessible text for an amount from the API. Response data is an open set: a value
 * or currency this version cannot format shows as unknown ("–") instead of failing the screen.
 */
export function amountTexts(options: AmountFormatOptions & { locale: UiLocale }): {
  display: string;
  label: string;
} {
  if (options.masked) return { display: MASK, label: uiMessage('amount.hidden', options.locale) };
  try {
    return { display: displayAmount(options), label: accessibleAmount(options) };
  } catch (error) {
    if (process.env.NODE_ENV !== 'production') {
      console.warn('Amount cannot be displayed:', (error as Error).message);
    }
    return { display: UNKNOWN, label: uiMessage('amount.unknown', options.locale) };
  }
}

export type AmountProps = AmountFormatOptions & {
  style?: TextStyle;
  accessibilityLabel?: string;
  testID?: string;
};
export function Amount({ style, accessibilityLabel, testID, ...options }: AmountProps) {
  const theme = useTheme();
  const locale = options.locale ?? currentUiLocale();
  const { display, label } = amountTexts({ ...options, locale });
  return (
    <Text
      testID={testID}
      // Masking wins over any caller-provided label: private digits cannot leak.
      accessibilityLabel={options.masked ? label : (accessibilityLabel ?? label)}
      style={[{ color: theme.textPrimary, fontVariant: ['tabular-nums'], flexShrink: 1 }, style]}
    >
      {display}
    </Text>
  );
}
