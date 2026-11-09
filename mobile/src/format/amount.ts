import type { IntlShape } from 'react-intl';

import type { DecimalString } from '@/api/decimal';

/**
 * The UI contract of #378 (docs/ux/check-amount-format.mjs): exact decimal strings, never
 * Number(). The string goes straight into Intl.NumberFormat (NumberFormat v3), which keeps every
 * digit; on Hermes that is the FormatJS polyfill loaded by src/i18n/polyfills.
 */
export type AmountOptions = {
  /** 'change' shows an explicit + on positive values. */
  signRole?: 'balance' | 'change';
  /** Prefix ≈ for an estimated value. */
  approx?: boolean;
  /** false for UNKNOWN values. */
  valueKnown?: boolean;
  /** Privacy masking is on. */
  masked?: boolean;
};

export type AmountIntl = Pick<IntlShape, 'locale' | 'messages' | 'formatMessage'>;

const PLAIN_DECIMAL = /^-?\d+(\.\d+)?$/;
const MINUS = '−'; // U+2212 MINUS SIGN
const NBSP = '\u00A0'; // U+00A0 between currency code and number
const HIDDEN = '••••';
const UNKNOWN = '–'; // U+2013 EN DASH
// UX-02 house format (PO 2026-11-09): every UI language displays the Swiss grouping, an ASCII
// apostrophe, and a decimal point, e.g. 1'111'111.00. Only the spoken form follows the locale.
const DISPLAY_GROUP = "'";
const DISPLAY_DECIMAL = '.';
// Formatted with 'en' and the separators replaced: Latin digits, one rule for every locale.
const DISPLAY_LOCALE = 'en';

const scaleCache = new Map<string, number>();
const formatterCache = new Map<string, Intl.NumberFormat>();

/** The currency's minor-unit scale (CHF/EUR 2, JPY 0), from CLDR. */
function currencyScale(currency: string): number {
  let scale = scaleCache.get(currency);
  if (scale === undefined) {
    scale = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits as number;
    scaleCache.set(currency, scale);
  }
  return scale;
}

function decimalFormatter(locale: string, scale: number): Intl.NumberFormat {
  const key = `${locale}|${scale}`;
  let formatter = formatterCache.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'decimal',
      minimumFractionDigits: scale,
      maximumFractionDigits: scale,
      roundingMode: 'halfExpand', // commercial rounding, display only
      useGrouping: true,
    } as Intl.NumberFormatOptions);
    formatterCache.set(key, formatter);
  }
  return formatter;
}

type FormattedNumber = { sign: '' | 'minus' | 'plus'; grouped: string; plain: string };

function formatNumber(
  value: DecimalString,
  currency: string,
  locale: string,
  signRole: AmountOptions['signRole'],
): FormattedNumber {
  if (typeof value !== 'string' || !PLAIN_DECIMAL.test(value)) {
    throw new TypeError('Expected a plain DecimalString');
  }
  const canonical = Intl.getCanonicalLocales(locale)[0] ?? locale;
  const negative = value.startsWith('-');
  // Type-only cast: NumberFormat v3 formats the string itself, the value never becomes a number.
  const exactInput = (negative ? value.slice(1) : value) as unknown as number;
  const scale = currencyScale(currency);
  const parts = decimalFormatter(DISPLAY_LOCALE, scale).formatToParts(exactInput);
  const zero = parts.every(
    (part) => !['integer', 'fraction'].includes(part.type) || /^0+$/.test(part.value),
  );
  return {
    sign: zero ? '' : negative ? 'minus' : signRole === 'change' ? 'plus' : '',
    grouped: parts
      .map((part) =>
        part.type === 'group'
          ? DISPLAY_GROUP
          : part.type === 'decimal'
            ? DISPLAY_DECIMAL
            : part.value,
      )
      .join(''),
    // Screen readers read separators aloud ("apostrophe"), so the spoken form has no grouping;
    // its decimal separator is the reader's locale ("Komma" in German).
    plain: decimalFormatter(canonical, scale)
      .formatToParts(exactInput)
      .filter((part) => part.type !== 'group')
      .map((part) => part.value)
      .join(''),
  };
}

export function formatAmount(
  value: DecimalString | null,
  currency: string,
  locale: string,
  options: AmountOptions = {},
): string {
  if (options.masked) return HIDDEN;
  if (options.valueKnown === false || value === null) return UNKNOWN;
  const { sign, grouped } = formatNumber(value, currency, locale, options.signRole);
  const signText = sign === 'minus' ? MINUS : sign === 'plus' ? '+' : '';
  return `${options.approx ? '≈' : ''}${signText}${currency}${NBSP}${grouped}`;
}

/**
 * The accessible label of an amount: sign and currency spelled out from the message catalogue
 * (`amount.a11y`, `currency.<code>`); a currency without a name is read as its code.
 */
export function a11yAmount(
  value: DecimalString | null,
  currency: string,
  intl: AmountIntl,
  options: AmountOptions = {},
): string {
  if (options.masked) return intl.formatMessage({ id: 'value.hidden' });
  if (value === null || options.valueKnown === false) {
    return intl.formatMessage({ id: 'value.unknown' });
  }
  const { sign, plain } = formatNumber(value, currency, intl.locale, options.signRole);
  const currencyId = `currency.${currency}`;
  return intl.formatMessage(
    { id: 'amount.a11y' },
    {
      approx: options.approx ? 'yes' : 'no',
      sign: sign || 'none',
      amount: plain,
      currency: currencyId in intl.messages ? intl.formatMessage({ id: currencyId }) : currency,
    },
  );
}
