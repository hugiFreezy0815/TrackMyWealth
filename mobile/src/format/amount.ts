import type { IntlShape } from 'react-intl';

import type { DecimalString } from '@/api/decimal';

import { decimalParts, isPlainDecimal, type DecimalParts, type SignRole } from './decimal-parts';

/**
 * The UI contract of #378 (docs/ux/check-amount-format.mjs): exact decimal strings, never
 * Number(). The string goes straight into Intl.NumberFormat (NumberFormat v3), which keeps every
 * digit; on Hermes that is the FormatJS polyfill loaded by src/i18n/polyfills.
 */
export type AmountOptions = {
  /** 'change' shows an explicit + on positive values. */
  signRole?: SignRole;
  /** Prefix ≈ for an estimated value. */
  approx?: boolean;
  /** false for UNKNOWN values. */
  valueKnown?: boolean;
  /** Privacy masking is on. */
  masked?: boolean;
};

export type AmountIntl = Pick<IntlShape, 'locale' | 'messages' | 'formatMessage'>;

const MINUS = '−'; // U+2212 MINUS SIGN
const NBSP = '\u00A0'; // U+00A0 between currency code and number
const HIDDEN = '••••';
const UNKNOWN = '–'; // U+2013 EN DASH

const scaleCache = new Map<string, number>();

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

function formatNumber(
  value: DecimalString,
  currency: string,
  locale: string,
  signRole: AmountOptions['signRole'],
): DecimalParts {
  // Validate before the currency lookup, so a bad value fails the same way for every currency.
  if (!isPlainDecimal(value)) throw new TypeError('Expected a plain DecimalString');
  const scale = currencyScale(currency);
  return decimalParts(value, { min: scale, max: scale }, locale, signRole);
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
