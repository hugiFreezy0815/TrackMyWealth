import type { DecimalString } from '@/api/decimal';

/**
 * The shared core of the UX-02 house number format (#378 section 3), used by `amount.ts` for money
 * and `figure.ts` for units, percentages and rates. The `DecimalString` goes straight into
 * Intl.NumberFormat (NumberFormat v3 keeps every digit); it never becomes a JavaScript number.
 */

const PLAIN_DECIMAL = /^-?\d+(\.\d+)?$/;
// UX-02 house format (PO 2026-11-09): every UI language displays the Swiss grouping, an ASCII
// apostrophe, and a decimal point, e.g. 1'111'111.00. Only the spoken form follows the locale.
const DISPLAY_GROUP = "'";
const DISPLAY_DECIMAL = '.';
// Formatted with 'en' and the separators replaced: Latin digits, one rule for every locale.
const DISPLAY_LOCALE = 'en';

export type SignRole = 'balance' | 'change';
export type FractionDigits = { min: number; max: number };
export type DecimalParts = { sign: '' | 'minus' | 'plus'; grouped: string; plain: string };

const formatterCache = new Map<string, Intl.NumberFormat>();

function decimalFormatter(locale: string, { min, max }: FractionDigits): Intl.NumberFormat {
  const key = `${locale}|${min}|${max}`;
  let formatter = formatterCache.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'decimal',
      minimumFractionDigits: min,
      maximumFractionDigits: max,
      roundingMode: 'halfExpand', // commercial rounding, display only
      useGrouping: true,
    } as Intl.NumberFormatOptions);
    formatterCache.set(key, formatter);
  }
  return formatter;
}

export function isPlainDecimal(value: unknown): value is DecimalString {
  return typeof value === 'string' && PLAIN_DECIMAL.test(value);
}

/**
 * Display (`grouped`) and spoken (`plain`) digits of `value`, plus its sign. The sign is decided
 * after rounding: a value that displays as zero has none; a positive value gets `plus` only in
 * the `change` role. Throws a TypeError for anything that is not a plain decimal string.
 */
export function decimalParts(
  value: DecimalString,
  fraction: FractionDigits,
  locale: string,
  signRole: SignRole = 'balance',
): DecimalParts {
  if (!isPlainDecimal(value)) {
    throw new TypeError('Expected a plain DecimalString');
  }
  const canonical = Intl.getCanonicalLocales(locale)[0] ?? locale;
  const negative = value.startsWith('-');
  // Type-only cast: NumberFormat v3 formats the string itself, the value never becomes a number.
  const exactInput = (negative ? value.slice(1) : value) as unknown as number;
  const parts = decimalFormatter(DISPLAY_LOCALE, fraction).formatToParts(exactInput);
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
    plain: decimalFormatter(canonical, fraction)
      .formatToParts(exactInput)
      .filter((part) => part.type !== 'group')
      .map((part) => part.value)
      .join(''),
  };
}
