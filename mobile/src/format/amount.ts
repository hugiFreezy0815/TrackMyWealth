import type { DecimalString } from '@/api/decimal';

/** The UI contract in docs/ux/check-amount-format.mjs: exact decimal strings, never Number(). */
export type AmountOptions = {
  signRole?: 'balance' | 'change';
  sign?: 'balance' | 'change';
  approx?: boolean;
  valueKnown?: boolean;
  masked?: boolean;
};
const PLAIN_DECIMAL = /^-?\d+(\.\d+)?$/;
const MINUS = '−';
const HIDDEN = '••••';
const UNKNOWN = '–';

export function formatAmount(
  value: DecimalString | null,
  currency: string,
  locale: string,
  options: AmountOptions = {},
): string {
  if (options.masked) return HIDDEN;
  if (!options.valueKnown && options.valueKnown !== undefined || value === null) return UNKNOWN;
  if (typeof value !== 'string' || !PLAIN_DECIMAL.test(value)) {
    throw new TypeError('Expected a plain DecimalString');
  }
  const scale = new Intl.NumberFormat('en', { style: 'currency', currency })
    .resolvedOptions().maximumFractionDigits;
  const negative = value.startsWith('-');
  const parts = new Intl.NumberFormat(locale, {
    style: 'decimal', minimumFractionDigits: scale, maximumFractionDigits: scale,
    roundingMode: 'halfExpand', useGrouping: true,
  } as Intl.NumberFormatOptions).formatToParts(negative ? value.slice(1) : value);
  const isZero = parts.every(
    (part) => !['integer', 'fraction'].includes(part.type) || /^0+$/.test(part.value),
  );
  const digits = parts.map((part) =>
    part.type === 'group' && locale === 'de-CH' ? "'" : part.value,
  ).join('');
  const sign = isZero ? '' : negative ? MINUS : (options.signRole ?? options.sign) === 'change' ? '+' : '';
  return `${options.approx ? '≈' : ''}${sign}${currency} ${digits}`;
}

const CURRENCY_NAMES: Record<string, Record<string, string>> = {
  CHF: { en: 'Swiss francs', de: 'Schweizer Franken' },
  EUR: { en: 'euros', de: 'Euro' },
  USD: { en: 'US dollars', de: 'US-Dollar' },
  JPY: { en: 'Japanese yen', de: 'Japanische Yen' },
};

export function a11yAmount(
  value: DecimalString | null, currency: string, locale: string, options: AmountOptions = {},
): string {
  if (options.masked) return locale.startsWith('de') ? 'verborgen' : 'hidden';
  if (value === null || options.valueKnown === false) return locale.startsWith('de') ? 'Wert unbekannt' : 'value unknown';
  const formatted = formatAmount(value, currency, locale, options);
  const number = formatted.replace(/^≈?[+−]?[^\s]+\s/, '');
  const sign = formatted.includes(MINUS) ? (locale.startsWith('de') ? 'minus ' : 'minus ') :
    formatted.includes('+') ? (locale.startsWith('de') ? 'plus ' : 'plus ') : '';
  const unit = CURRENCY_NAMES[currency]?.[locale.slice(0, 2)] ?? currency;
  return `${options.approx ? (locale.startsWith('de') ? 'ungefähr ' : 'approximately ') : ''}${sign}${number} ${unit}`;
}
